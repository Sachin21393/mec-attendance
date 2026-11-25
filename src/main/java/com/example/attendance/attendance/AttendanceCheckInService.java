package com.example.attendance.attendance;

import com.example.attendance.audit.AuditActorType;
import com.example.attendance.audit.AuditService;
import com.example.attendance.auth.TokenHashUtil;
import com.example.attendance.biometrics.BiometricVerificationToken;
import com.example.attendance.biometrics.BiometricVerificationTokenRepository;
import com.example.attendance.common.ConflictException;
import com.example.attendance.common.ForbiddenException;
import com.example.attendance.common.ResourceNotFoundException;
import com.example.attendance.common.ValidationException;
import com.example.attendance.settings.AttendanceSettings;
import com.example.attendance.settings.AttendanceSettingsRepository;
import com.example.attendance.settings.DayType;
import com.example.attendance.settings.WorkCalendarDay;
import com.example.attendance.settings.WorkCalendarRepository;
import com.example.attendance.workers.Worker;
import com.example.attendance.workers.WorkerRepository;
import com.example.attendance.workers.WorkerStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class AttendanceCheckInService {

    private final AttendanceRecordRepository attendanceRepository;
    private final WorkerRepository workerRepository;
    private final AttendanceSettingsRepository settingsRepository;
    private final WorkCalendarRepository calendarRepository;
    private final BiometricVerificationTokenRepository verificationTokenRepository;
    private final AttendanceCalculator calculator;
    private final AuditService auditService;
    private final Clock clock;

    public AttendanceCheckInService(
            AttendanceRecordRepository attendanceRepository,
            WorkerRepository workerRepository,
            AttendanceSettingsRepository settingsRepository,
            WorkCalendarRepository calendarRepository,
            BiometricVerificationTokenRepository verificationTokenRepository,
            AttendanceCalculator calculator,
            AuditService auditService,
            Clock clock
    ) {
        this.attendanceRepository = attendanceRepository;
        this.workerRepository = workerRepository;
        this.settingsRepository = settingsRepository;
        this.calendarRepository = calendarRepository;
        this.verificationTokenRepository = verificationTokenRepository;
        this.calculator = calculator;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public TodayStatusResponse getTodayStatus(UUID workerId) {
        Worker worker = workerRepository.findById(workerId)
                .orElseThrow(() -> new ResourceNotFoundException("WORKER_NOT_FOUND", "Worker not found."));
        AttendanceSettings settings = loadSettings();
        Instant now = clock.instant();
        LocalDate attendanceDate = calculator.resolveAttendanceDate(now, settings);
        WorkCalendarDay calDay = calendarRepository.findByCalendarDate(attendanceDate).orElse(null);
        AttendanceCalculator.EffectiveDayConfig dayConfig = calculator.resolveDayConfig(attendanceDate, calDay, settings);

        long proxyUsedToday = attendanceRepository.countByActorWorkerIdAndAttendanceDateAndAttendanceMethod(
                workerId, attendanceDate, AttendanceMethod.PROXY);
        int maxProxy = settings.isProxyEnabled() ? settings.getMaxProxyPerActorPerDay() : 0;

        Optional<AttendanceRecord> existingOpt = attendanceRepository.findBySubjectWorkerIdAndAttendanceDate(workerId, attendanceDate);
        if (existingOpt.isEmpty()) {
            return new TodayStatusResponse(
                    attendanceDate,
                    dayConfig.timezone().getId(),
                    dayConfig.dayType(),
                    dayConfig.shiftStartLocal().toString(),
                    dayConfig.gracePeriodMinutes(),
                    settings.isProxyEnabled(),
                    maxProxy,
                    (int) proxyUsedToday,
                    false,
                    null,
                    null,
                    null,
                    null,
                    null,
                    worker.getFullName(),
                    null
            );
        }

        AttendanceRecord rec = existingOpt.get();
        String actorName = rec.getActorWorkerId() == null ? "Admin"
                : workerRepository.findById(rec.getActorWorkerId()).map(Worker::getFullName).orElse("Worker");
        return new TodayStatusResponse(
                attendanceDate,
                dayConfig.timezone().getId(),
                dayConfig.dayType(),
                dayConfig.shiftStartLocal().toString(),
                dayConfig.gracePeriodMinutes(),
                settings.isProxyEnabled(),
                maxProxy,
                (int) proxyUsedToday,
                true,
                rec.getId(),
                rec.getAttendanceStatus(),
                rec.getAttendanceMethod(),
                rec.getCheckInAtUtc(),
                calculator.formatLocalTime(rec.getCheckInAtUtc(), dayConfig.timezone()),
                worker.getFullName(),
                actorName
        );
    }

    @Transactional(readOnly = true)
    public List<WorkerHistoryEntry> getWorkerHistory(UUID workerId, YearMonth month) {
        AttendanceSettings settings = loadSettings();
        ZoneId zone = calculator.resolveZone(settings);
        LocalDate start = month.atDay(1);
        LocalDate end = month.atEndOfMonth();
        return attendanceRepository
                .findBySubjectWorkerIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(workerId, start, end)
                .stream()
                .map(rec -> new WorkerHistoryEntry(
                        rec.getId(),
                        rec.getAttendanceDate(),
                        rec.getAttendanceStatus(),
                        rec.getAttendanceMethod(),
                        rec.getCheckInAtUtc(),
                        calculator.formatLocalTime(rec.getCheckInAtUtc(), zone)
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public KioskStatusResponse getKioskStatus() {
        AttendanceSettings settings = loadSettings();
        Instant now = clock.instant();
        LocalDate attendanceDate = calculator.resolveAttendanceDate(now, settings);
        WorkCalendarDay calDay = calendarRepository.findByCalendarDate(attendanceDate).orElse(null);
        AttendanceCalculator.EffectiveDayConfig dayConfig = calculator.resolveDayConfig(attendanceDate, calDay, settings);
        return new KioskStatusResponse(
                settings.getWorkplaceName(),
                attendanceDate,
                dayConfig.timezone().getId(),
                dayConfig.dayType(),
                dayConfig.shiftStartLocal().toString(),
                dayConfig.gracePeriodMinutes()
        );
    }

    @Transactional
    public CheckInResponse checkIn(UUID actorWorkerId, UUID sessionId, String rawVerificationToken, String idempotencyKey) {
        String cleanIdemKey = requireIdempotencyKey(idempotencyKey);

        Optional<AttendanceRecord> replayOpt = attendanceRepository.findByIdempotencyKey(cleanIdemKey);
        if (replayOpt.isPresent()) {
            AttendanceRecord existing = replayOpt.get();
            if (!actorWorkerId.equals(existing.getActorWorkerId())) {
                throw new ConflictException("IDEMPOTENCY_CONFLICT", "This request key belongs to another worker.");
            }
            return buildReplayResponse(existing, actorWorkerId);
        }

        BiometricVerificationToken bioToken = validateTokenForUse(rawVerificationToken);
        if ("KIOSK".equalsIgnoreCase(bioToken.getVerificationMode())) {
            throw new ForbiddenException("VERIFICATION_MODE_MISMATCH", "Use kiosk check-in for kiosk verification tokens.");
        }
        if (!bioToken.getActorWorkerId().equals(actorWorkerId)) {
            throw new ForbiddenException("VERIFICATION_ACTOR_MISMATCH", "Verification token does not belong to the signed-in worker.");
        }
        return executeCheckIn(actorWorkerId, sessionId, bioToken, cleanIdemKey);
    }

    /**
     * Completes a direct kiosk attendance check-in using a single-use KIOSK verification token
     * without requiring a prior personal worker session cookie on the device.
     */
    @Transactional
    public CheckInResponse kioskCheckIn(String rawVerificationToken, String idempotencyKey) {
        String cleanIdemKey = requireIdempotencyKey(idempotencyKey);

        Optional<AttendanceRecord> replayOpt = attendanceRepository.findByIdempotencyKey(cleanIdemKey);
        if (replayOpt.isPresent()) {
            AttendanceRecord existing = replayOpt.get();
            UUID actorId = existing.getActorWorkerId() != null ? existing.getActorWorkerId() : existing.getSubjectWorkerId();
            return buildReplayResponse(existing, actorId);
        }

        BiometricVerificationToken bioToken = validateTokenForUse(rawVerificationToken);
        if (!"KIOSK".equalsIgnoreCase(bioToken.getVerificationMode())) {
            throw new ForbiddenException("VERIFICATION_MODE_MISMATCH", "Invalid verification token mode for direct attendance.");
        }
        return executeCheckIn(bioToken.getSubjectWorkerId(), null, bioToken, cleanIdemKey);
    }

    private static String requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ValidationException("IDEMPOTENCY_KEY_REQUIRED", "An idempotency key is required for check-in.");
        }
        return idempotencyKey.trim();
    }

    private CheckInResponse buildReplayResponse(AttendanceRecord existing, UUID actorWorkerId) {
        AttendanceSettings settings = loadSettings();
        ZoneId zone = calculator.resolveZone(settings);
        Worker subject = workerRepository.findById(existing.getSubjectWorkerId()).orElseThrow();
        Worker actor = workerRepository.findById(actorWorkerId).orElse(subject);
        return new CheckInResponse(
                existing.getId(),
                subject.getId(),
                subject.getWorkerCode(),
                subject.getFullName(),
                actor.getId(),
                actor.getFullName(),
                existing.getAttendanceDate(),
                existing.getCheckInAtUtc(),
                calculator.formatLocalTime(existing.getCheckInAtUtc(), zone),
                zone.getId(),
                existing.getAttendanceStatus(),
                existing.getAttendanceMethod(),
                true
        );
    }

    private BiometricVerificationToken validateTokenForUse(String rawVerificationToken) {
        if (rawVerificationToken == null || rawVerificationToken.isBlank()) {
            throw new ValidationException("VERIFICATION_TOKEN_REQUIRED", "Face verification is required before marking attendance.");
        }
        String tokenHash = TokenHashUtil.sha256Hex(rawVerificationToken.trim());
        BiometricVerificationToken bioToken = verificationTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new ValidationException("INVALID_VERIFICATION_TOKEN", "Face verification token is invalid."));

        Instant now = clock.instant();
        if (bioToken.getConsumedAtUtc() != null) {
            throw new ConflictException("VERIFICATION_TOKEN_USED", "This face verification has already been used. Please scan again.");
        }
        if (!bioToken.getExpiresAtUtc().isAfter(now)) {
            throw new ValidationException("VERIFICATION_TOKEN_EXPIRED", "Face verification expired. Please scan again.");
        }
        return bioToken;
    }

    private CheckInResponse executeCheckIn(
            UUID actorWorkerId,
            UUID sessionId,
            BiometricVerificationToken bioToken,
            String cleanIdemKey
    ) {
        Instant now = clock.instant();

        Worker actor = workerRepository.findById(actorWorkerId)
                .orElseThrow(() -> new ResourceNotFoundException("WORKER_NOT_FOUND", "Actor worker not found."));
        if (actor.getStatus() != WorkerStatus.REGISTERED) {
            throw new ForbiddenException("WORKER_NOT_ACTIVE", "Worker account is not active.");
        }

        AttendanceMethod method = "PROXY".equalsIgnoreCase(bioToken.getVerificationMode())
                ? AttendanceMethod.PROXY
                : AttendanceMethod.SELF;

        AttendanceSettings settings = loadSettings();
        LocalDate attendanceDate = calculator.resolveAttendanceDate(now, settings);
        WorkCalendarDay calDay = calendarRepository.findByCalendarDate(attendanceDate).orElse(null);
        AttendanceCalculator.EffectiveDayConfig dayConfig = calculator.resolveDayConfig(attendanceDate, calDay, settings);

        if (dayConfig.dayType() != DayType.WORKING) {
            throw new ConflictException(
                    "NON_WORKING_DAY",
                    "Today (" + attendanceDate + ") is configured as " + dayConfig.dayType().name().replace('_', ' ').toLowerCase() + "."
            );
        }

        Worker subject;
        if (method == AttendanceMethod.PROXY) {
            if (!settings.isProxyEnabled()) {
                throw new ForbiddenException("PROXY_DISABLED", "Marking attendance for another worker is disabled by policy.");
            }
            if (bioToken.getSubjectWorkerId().equals(actor.getId())) {
                throw new ValidationException("SELF_PROXY_NOT_ALLOWED", "Use regular self check-in for your own attendance.");
            }
            // Serialize concurrent proxy attempts by locking the actor worker row
            workerRepository.findByIdForUpdate(actor.getId())
                    .orElseThrow(() -> new ResourceNotFoundException("WORKER_NOT_FOUND", "Actor worker not found."));
            long usedProxyToday = attendanceRepository.countByActorWorkerIdAndAttendanceDateAndAttendanceMethod(
                    actor.getId(), attendanceDate, AttendanceMethod.PROXY);
            if (usedProxyToday >= settings.getMaxProxyPerActorPerDay()) {
                auditService.recordIndependent(
                        "ATTENDANCE_PROXY_CAP_REACHED",
                        AuditActorType.WORKER,
                        actor.getId(),
                        bioToken.getSubjectWorkerId(),
                        Map.of(
                                "attendanceDate", attendanceDate.toString(),
                                "usedProxyToday", usedProxyToday,
                                "maxProxyPerDay", settings.getMaxProxyPerActorPerDay()
                        )
                );
                throw new ForbiddenException(
                        "PROXY_LIMIT_REACHED",
                        "You have reached the daily limit (" + settings.getMaxProxyPerActorPerDay() + ") for marking attendance for another worker."
                );
            }
            subject = workerRepository.findById(bioToken.getSubjectWorkerId())
                    .orElseThrow(() -> new ResourceNotFoundException("WORKER_NOT_FOUND", "Subject worker not found."));
            if (subject.getStatus() != WorkerStatus.REGISTERED) {
                throw new ForbiddenException("TARGET_WORKER_NOT_ACTIVE", "Target worker account is not active.");
            }
        } else {
            if (!bioToken.getSubjectWorkerId().equals(actor.getId())) {
                throw new ForbiddenException("VERIFICATION_SUBJECT_MISMATCH", "Verification token does not match the signed-in worker.");
            }
            subject = actor;
        }

        Optional<AttendanceRecord> alreadyMarked = attendanceRepository.findBySubjectWorkerIdAndAttendanceDate(subject.getId(), attendanceDate);
        if (alreadyMarked.isPresent()) {
            String timeStr = calculator.formatLocalTime(alreadyMarked.get().getCheckInAtUtc(), dayConfig.timezone());
            String msg = method == AttendanceMethod.PROXY
                    ? subject.getFullName() + " has already marked attendance today at " + timeStr
                    : "You have already marked attendance today at " + timeStr;
            throw new ConflictException("ALREADY_MARKED", msg);
        }

        AttendanceStatus status = calculator.evaluateCheckInStatus(now, dayConfig);
        bioToken.setConsumedAtUtc(now);
        verificationTokenRepository.save(bioToken);

        String note = method == AttendanceMethod.PROXY
                ? "Marked on behalf by " + actor.getWorkerCode() + (bioToken.isMockMode() ? " (Mock biometrics mode)" : "")
                : (bioToken.isMockMode() ? "Mock biometrics mode" : null);

        AttendanceRecord record = new AttendanceRecord(
                UUID.randomUUID(),
                subject.getId(),
                actor.getId(),
                attendanceDate,
                now,
                status,
                method,
                cleanIdemKey,
                bioToken.getMatchConfidence(),
                sessionId,
                note,
                now
        );

        try {
            attendanceRepository.saveAndFlush(record);
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("ALREADY_MARKED", "Attendance has already been marked for today.");
        }

        auditService.record(
                method == AttendanceMethod.PROXY ? "ATTENDANCE_PROXY_RECORDED" : "ATTENDANCE_SELF_RECORDED",
                AuditActorType.WORKER,
                actor.getId(),
                subject.getId(),
                Map.of(
                        "attendanceDate", attendanceDate.toString(),
                        "status", status.name(),
                        "method", method.name(),
                        "actorWorkerCode", actor.getWorkerCode(),
                        "subjectWorkerCode", subject.getWorkerCode(),
                        "mockMode", bioToken.isMockMode()
                )
        );

        return new CheckInResponse(
                record.getId(),
                subject.getId(),
                subject.getWorkerCode(),
                subject.getFullName(),
                actor.getId(),
                actor.getFullName(),
                attendanceDate,
                now,
                calculator.formatLocalTime(now, dayConfig.timezone()),
                dayConfig.timezone().getId(),
                status,
                method,
                false
        );
    }

    private AttendanceSettings loadSettings() {
        return settingsRepository.findTopByOrderByEffectiveFromUtcDesc()
                .orElseThrow(() -> new IllegalStateException("Attendance settings not found."));
    }

    public record TodayStatusResponse(
            LocalDate attendanceDate,
            String timezoneId,
            DayType dayType,
            String shiftStartLocal,
            int gracePeriodMinutes,
            boolean proxyEnabled,
            int maxProxyPerDay,
            int proxyUsedToday,
            boolean alreadyMarked,
            UUID recordId,
            AttendanceStatus attendanceStatus,
            AttendanceMethod attendanceMethod,
            Instant checkInAtUtc,
            String checkInLocalTime,
            String workerName,
            String markedByName
    ) {
    }

    public record WorkerHistoryEntry(
            UUID id,
            LocalDate attendanceDate,
            AttendanceStatus attendanceStatus,
            AttendanceMethod attendanceMethod,
            Instant checkInAtUtc,
            String checkInLocalTime
    ) {
    }

    public record CheckInResponse(
            UUID id,
            UUID subjectWorkerId,
            String subjectWorkerCode,
            String subjectWorkerName,
            UUID actorWorkerId,
            String actorWorkerName,
            LocalDate attendanceDate,
            Instant checkInAtUtc,
            String checkInLocalTime,
            String timezoneId,
            AttendanceStatus attendanceStatus,
            AttendanceMethod attendanceMethod,
            boolean idempotentReplay
    ) {
    }

    public record KioskStatusResponse(
            String workplaceName,
            LocalDate attendanceDate,
            String timezoneId,
            DayType dayType,
            String shiftStartLocal,
            int gracePeriodMinutes
    ) {
    }
}
