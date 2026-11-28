package com.example.attendance.leaves;

import com.example.attendance.attendance.AttendanceCalculator;
import com.example.attendance.attendance.AttendanceRecord;
import com.example.attendance.attendance.AttendanceRecordRepository;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class LeaveService {

    private static final Logger log = LoggerFactory.getLogger(LeaveService.class);

    private final LeaveRecordRepository leaveRepository;
    private final AttendanceRecordRepository attendanceRepository;
    private final WorkerRepository workerRepository;
    private final AttendanceSettingsRepository settingsRepository;
    private final WorkCalendarRepository calendarRepository;
    private final BiometricVerificationTokenRepository verificationTokenRepository;
    private final AttendanceCalculator calculator;
    private final AuditService auditService;
    private final Clock clock;

    public LeaveService(
            LeaveRecordRepository leaveRepository,
            AttendanceRecordRepository attendanceRepository,
            WorkerRepository workerRepository,
            AttendanceSettingsRepository settingsRepository,
            WorkCalendarRepository calendarRepository,
            BiometricVerificationTokenRepository verificationTokenRepository,
            AttendanceCalculator calculator,
            AuditService auditService,
            Clock clock
    ) {
        this.leaveRepository = leaveRepository;
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
    public Optional<LeaveMarkResponse> findIdempotentReplay(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return leaveRepository.findByIdempotencyKey(idempotencyKey.trim())
                .map(existing -> {
                    AttendanceSettings settings = loadSettings();
                    Worker subject = workerRepository.findById(existing.getWorkerId()).orElseThrow();
                    return buildMarkResponse(subject, existing, settings, true);
                });
    }

    @Transactional
    public LeaveMarkResponse markKioskLeave(
            String rawVerificationToken,
            LeaveType leaveType,
            String idempotencyKey
    ) {
        if (leaveType == null) {
            throw new ValidationException("LEAVE_TYPE_REQUIRED", "Please select Sick / Paid Leave or Annual Leave.");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ValidationException("IDEMPOTENCY_KEY_REQUIRED", "An idempotency key is required.");
        }
        String cleanKey = idempotencyKey.trim();

        Optional<LeaveRecord> replayOpt = leaveRepository.findByIdempotencyKey(cleanKey);
        if (replayOpt.isPresent()) {
            AttendanceSettings settings = loadSettings();
            Worker subject = workerRepository.findById(replayOpt.get().getWorkerId()).orElseThrow();
            return buildMarkResponse(subject, replayOpt.get(), settings, true);
        }

        BiometricVerificationToken bioToken = validateTokenForUse(rawVerificationToken);
        if (!"KIOSK".equalsIgnoreCase(bioToken.getVerificationMode())) {
            throw new ForbiddenException("VERIFICATION_MODE_MISMATCH", "Invalid verification token mode for direct leave marking.");
        }

        Instant now = clock.instant();
        Worker subject = workerRepository.findById(bioToken.getSubjectWorkerId())
                .orElseThrow(() -> new ResourceNotFoundException("WORKER_NOT_FOUND", "Employee not found."));
        if (subject.getStatus() != WorkerStatus.REGISTERED) {
            throw new ForbiddenException("WORKER_NOT_ACTIVE", "Employee account is not active.");
        }

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

        Optional<AttendanceRecord> existingAttendance = attendanceRepository.findBySubjectWorkerIdAndAttendanceDate(subject.getId(), attendanceDate);
        if (existingAttendance.isPresent()) {
            String timeStr = calculator.formatLocalTime(existingAttendance.get().getCheckInAtUtc(), dayConfig.timezone());
            throw new ConflictException(
                    "ALREADY_MARKED",
                    subject.getFullName() + " (" + subject.getWorkerCode() + ") has already marked Login Attendance today at " + timeStr
            );
        }

        Optional<LeaveRecord> existingLeave = leaveRepository.findByWorkerIdAndLeaveDate(subject.getId(), attendanceDate);
        LeaveRecord saved;
        if (existingLeave.isPresent()) {
            LeaveRecord rec = existingLeave.get();
            if (rec.getSource() != LeaveSource.AUTO_CUTOFF) {
                throw new ConflictException(
                        "LEAVE_ALREADY_MARKED",
                        subject.getFullName() + " (" + subject.getWorkerCode() + ") already marked "
                                + rec.getLeaveType().getDisplayLabel() + " for today."
                );
            }
            rec.setLeaveType(leaveType);
            rec.setSource(LeaveSource.EMPLOYEE_SELF);
            rec.setNotes("Marked by employee via face recognition");
            rec.setUpdatedAt(now);
            saved = leaveRepository.saveAndFlush(rec);
        } else {
            LeaveRecord rec = new LeaveRecord(
                    UUID.randomUUID(),
                    subject.getId(),
                    attendanceDate,
                    leaveType,
                    LeaveSource.EMPLOYEE_SELF,
                    "Marked by employee via face recognition",
                    cleanKey,
                    bioToken.getMatchConfidence(),
                    now
            );
            try {
                saved = leaveRepository.saveAndFlush(rec);
            } catch (DataIntegrityViolationException ex) {
                throw new ConflictException("LEAVE_ALREADY_MARKED", "Leave has already been marked for today.");
            }
        }

        bioToken.setConsumedAtUtc(now);
        verificationTokenRepository.save(bioToken);

        auditService.record(
                "LEAVE_RECORDED",
                AuditActorType.WORKER,
                subject.getId(),
                subject.getId(),
                Map.of(
                        "leaveDate", attendanceDate.toString(),
                        "leaveType", leaveType.name(),
                        "leaveLabel", leaveType.getDisplayLabel(),
                        "workerCode", subject.getWorkerCode(),
                        "source", LeaveSource.EMPLOYEE_SELF.name()
                )
        );

        return buildMarkResponse(subject, saved, settings, false);
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    @Transactional
    public void scheduledAutoCutoffSweep() {
        try {
            AttendanceSettings settings = settingsRepository.findTopByOrderByEffectiveFromUtcDesc().orElse(null);
            if (settings == null || !settings.isAutoMarkLeaveAfterCutoff()) {
                return;
            }
            LocalDate today = calculator.resolveAttendanceDate(clock.instant(), settings);
            LocalDate start = today.withDayOfMonth(1);
            applyAutoCutoffLeavesForRange(start, today);
        } catch (Exception ex) {
            log.warn("Auto cutoff leave sweep failed: {}", ex.getMessage());
        }
    }

    @Transactional
    public int applyAutoCutoffLeavesForMonth(YearMonth month) {
        return applyAutoCutoffLeavesForRange(month.atDay(1), month.atEndOfMonth());
    }

    @Transactional
    public int applyAutoCutoffLeavesForRange(LocalDate rangeStart, LocalDate rangeEnd) {
        AttendanceSettings settings = loadSettings();
        if (!settings.isAutoMarkLeaveAfterCutoff()) {
            return 0;
        }
        Instant now = clock.instant();
        LocalDate workplaceToday = calculator.resolveAttendanceDate(now, settings);
        LocalDate effectiveFloor = settings.getAutoLeaveEffectiveFromDate() != null
                ? settings.getAutoLeaveEffectiveFromDate()
                : workplaceToday;

        LocalDate start = rangeStart.isBefore(effectiveFloor) ? effectiveFloor : rangeStart;
        LocalDate end = rangeEnd.isAfter(workplaceToday) ? workplaceToday : rangeEnd;
        if (start.isAfter(end)) {
            return 0;
        }

        Map<LocalDate, WorkCalendarDay> calMap = calendarRepository.findByCalendarDateBetweenOrderByCalendarDateAsc(start, end)
                .stream()
                .collect(Collectors.toMap(WorkCalendarDay::getCalendarDate, Function.identity()));

        List<LocalDate> cutoffPassedWorkingDates = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            AttendanceCalculator.EffectiveDayConfig dc = calculator.resolveDayConfig(d, calMap.get(d), settings);
            if (dc.dayType() != DayType.WORKING) {
                continue;
            }
            LocalDateTime cutoff = calculator.resolveCutoffLocalDateTime(dc);
            LocalDateTime nowLocal = now.atZone(dc.timezone()).toLocalDateTime();
            if (nowLocal.isAfter(cutoff)) {
                cutoffPassedWorkingDates.add(d);
            }
        }
        if (cutoffPassedWorkingDates.isEmpty()) {
            return 0;
        }

        List<Worker> registeredWorkers = workerRepository.findAll(Sort.by("workerCode").ascending()).stream()
                .filter(w -> w.getStatus() == WorkerStatus.REGISTERED)
                .toList();
        if (registeredWorkers.isEmpty()) {
            return 0;
        }

        Map<LocalDate, Map<UUID, AttendanceRecord>> attByDate = new HashMap<>();
        for (AttendanceRecord r : attendanceRepository.findByAttendanceDateBetween(start, end)) {
            attByDate.computeIfAbsent(r.getAttendanceDate(), k -> new HashMap<>()).put(r.getSubjectWorkerId(), r);
        }

        Map<LocalDate, Map<UUID, LeaveRecord>> leaveByDate = new HashMap<>();
        for (LeaveRecord l : leaveRepository.findByLeaveDateBetween(start, end)) {
            leaveByDate.computeIfAbsent(l.getLeaveDate(), k -> new HashMap<>()).put(l.getWorkerId(), l);
        }

        Map<UUID, int[]> takenTotals = loadAllLeaveCountsByWorker();
        int createdCount = 0;

        for (LocalDate date : cutoffPassedWorkingDates) {
            AttendanceCalculator.EffectiveDayConfig dc = calculator.resolveDayConfig(date, calMap.get(date), settings);
            for (Worker w : registeredWorkers) {
                if (!isEmployedOn(w, date)) {
                    continue;
                }
                if (attByDate.getOrDefault(date, Map.of()).containsKey(w.getId())) {
                    continue;
                }
                if (leaveByDate.getOrDefault(date, Map.of()).containsKey(w.getId())) {
                    continue;
                }

                int[] counts = takenTotals.computeIfAbsent(w.getId(), id -> new int[2]);
                int paidTaken = counts[0];
                int annualTaken = counts[1];
                LeaveType autoType = (annualTaken < settings.getDefaultAnnualLeaveQuota()
                        || paidTaken >= settings.getDefaultPaidLeaveQuota())
                        ? LeaveType.ANNUAL_LEAVE
                        : LeaveType.SICK_LEAVE;

                LeaveRecord autoLeave = new LeaveRecord(
                        UUID.randomUUID(),
                        w.getId(),
                        date,
                        autoType,
                        LeaveSource.AUTO_CUTOFF,
                        "Automatically marked as leave after cutoff (" + dc.absenceCutoffLocal() + ")",
                        null,
                        null,
                        now
                );
                leaveRepository.save(autoLeave);
                if (autoType == LeaveType.SICK_LEAVE) {
                    counts[0]++;
                } else {
                    counts[1]++;
                }
                createdCount++;
                auditService.record(
                        "LEAVE_AUTO_MARKED_AFTER_CUTOFF",
                        AuditActorType.SYSTEM,
                        null,
                        w.getId(),
                        Map.of(
                                "leaveDate", date.toString(),
                                "leaveType", autoType.name(),
                                "workerCode", w.getWorkerCode(),
                                "cutoffTime", dc.absenceCutoffLocal().toString()
                        )
                );
            }
        }
        return createdCount;
    }

    @Transactional
    public AdminLeaveOverviewResponse getAdminLeaveOverview(YearMonth month, String query, String team) {
        applyAutoCutoffLeavesForMonth(month);
        AttendanceSettings settings = loadSettings();
        ZoneId zone = calculator.resolveZone(settings);

        LocalDate start = month.atDay(1);
        LocalDate end = month.atEndOfMonth();
        Map<LocalDate, WorkCalendarDay> calMap = calendarRepository.findByCalendarDateBetweenOrderByCalendarDateAsc(start, end)
                .stream()
                .collect(Collectors.toMap(WorkCalendarDay::getCalendarDate, Function.identity()));

        int totalWorkingDays = 0;
        List<LocalDate> monthDates = new ArrayList<>(month.lengthOfMonth());
        for (int d = 1; d <= month.lengthOfMonth(); d++) {
            LocalDate date = month.atDay(d);
            monthDates.add(date);
            if (calculator.resolveDayConfig(date, calMap.get(date), settings).dayType() == DayType.WORKING) {
                totalWorkingDays++;
            }
        }

        List<Worker> allWorkers = workerRepository.findAll(Sort.by("workerCode").ascending());
        String q = query == null ? "" : query.trim().toLowerCase();
        String t = team == null ? "" : team.trim().toLowerCase();

        List<Worker> activeWorkers = allWorkers.stream()
                .filter(w -> w.getStatus() != WorkerStatus.DISABLED)
                .filter(w -> q.isEmpty()
                        || w.getFullName().toLowerCase().contains(q)
                        || w.getWorkerCode().toLowerCase().contains(q))
                .filter(w -> t.isEmpty()
                        || (w.getTeamName() != null && w.getTeamName().toLowerCase().equals(t)))
                .toList();

        Map<UUID, Worker> workersById = allWorkers.stream()
                .collect(Collectors.toMap(Worker::getId, Function.identity()));
        Map<UUID, int[]> allLeaveCounts = loadAllLeaveCountsByWorker();
        List<LeaveRecord> monthLeaves = leaveRepository.findByLeaveDateBetweenOrderByLeaveDateDescCreatedAtDesc(start, end);
        Map<UUID, int[]> monthLeaveCounts = new HashMap<>();
        for (LeaveRecord rec : monthLeaves) {
            int[] arr = monthLeaveCounts.computeIfAbsent(rec.getWorkerId(), k -> new int[2]);
            if (rec.getLeaveType() == LeaveType.SICK_LEAVE) {
                arr[0]++;
            } else {
                arr[1]++;
            }
        }

        int paidQuota = settings.getDefaultPaidLeaveQuota();
        int annualQuota = settings.getDefaultAnnualLeaveQuota();

        int totalPaidTakenAll = 0;
        int totalAnnualTakenAll = 0;
        int totalPendingAll = 0;

        List<EmployeeLeaveBalanceRow> rows = new ArrayList<>(activeWorkers.size());
        for (Worker w : activeWorkers) {
            int applicableWorking = 0;
            for (LocalDate d : monthDates) {
                if (isEmployedOn(w, d)
                        && calculator.resolveDayConfig(d, calMap.get(d), settings).dayType() == DayType.WORKING) {
                    applicableWorking++;
                }
            }
            int[] totalArr = allLeaveCounts.getOrDefault(w.getId(), new int[2]);
            int[] monthArr = monthLeaveCounts.getOrDefault(w.getId(), new int[2]);
            int paidTaken = totalArr[0];
            int annualTaken = totalArr[1];
            int paidPending = Math.max(0, paidQuota - paidTaken);
            int annualPending = Math.max(0, annualQuota - annualTaken);
            int totalTaken = paidTaken + annualTaken;
            int totalPending = paidPending + annualPending;

            totalPaidTakenAll += paidTaken;
            totalAnnualTakenAll += annualTaken;
            totalPendingAll += totalPending;

            rows.add(new EmployeeLeaveBalanceRow(
                    w.getId(),
                    w.getWorkerCode(),
                    w.getFullName(),
                    w.getTeamName(),
                    w.getStatus(),
                    totalWorkingDays,
                    applicableWorking,
                    monthArr[0] + monthArr[1],
                    paidQuota,
                    paidTaken,
                    paidPending,
                    annualQuota,
                    annualTaken,
                    annualPending,
                    totalTaken,
                    totalPending
            ));
        }

        List<LeaveEntryDto> entries = monthLeaves.stream()
                .filter(rec -> {
                    Worker w = workersById.get(rec.getWorkerId());
                    if (w == null) return false;
                    boolean matchesQ = q.isEmpty()
                            || w.getFullName().toLowerCase().contains(q)
                            || w.getWorkerCode().toLowerCase().contains(q);
                    boolean matchesT = t.isEmpty()
                            || (w.getTeamName() != null && w.getTeamName().toLowerCase().equals(t));
                    return matchesQ && matchesT;
                })
                .map(rec -> {
                    Worker w = workersById.get(rec.getWorkerId());
                    return new LeaveEntryDto(
                            rec.getId(),
                            rec.getWorkerId(),
                            w.getWorkerCode(),
                            w.getFullName(),
                            w.getTeamName(),
                            rec.getLeaveDate(),
                            rec.getLeaveType(),
                            rec.getLeaveType().getDisplayLabel(),
                            rec.getSource(),
                            rec.getSource().getDisplayLabel(),
                            "APPROVED",
                            rec.getNotes(),
                            rec.getCreatedAt()
                    );
                })
                .toList();

        List<String> teams = allWorkers.stream()
                .map(Worker::getTeamName)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .sorted()
                .toList();

        return new AdminLeaveOverviewResponse(
                month.toString(),
                settings.getWorkplaceName(),
                zone.getId(),
                totalWorkingDays,
                paidQuota,
                annualQuota,
                settings.isAutoMarkLeaveAfterCutoff(),
                totalPaidTakenAll + totalAnnualTakenAll,
                totalPendingAll,
                totalPaidTakenAll,
                totalAnnualTakenAll,
                teams,
                rows,
                entries
        );
    }

    @Transactional(readOnly = true)
    public Map<UUID, int[]> loadAllLeaveCountsByWorker() {
        Map<UUID, int[]> map = new HashMap<>();
        for (LeaveRecord rec : leaveRepository.findAll()) {
            int[] arr = map.computeIfAbsent(rec.getWorkerId(), k -> new int[2]);
            if (rec.getLeaveType() == LeaveType.SICK_LEAVE) {
                arr[0]++;
            } else {
                arr[1]++;
            }
        }
        return map;
    }

    private LeaveMarkResponse buildMarkResponse(Worker subject, LeaveRecord rec, AttendanceSettings settings, boolean replay) {
        int paidTaken = (int) leaveRepository.countByWorkerIdAndLeaveType(subject.getId(), LeaveType.SICK_LEAVE);
        int annualTaken = (int) leaveRepository.countByWorkerIdAndLeaveType(subject.getId(), LeaveType.ANNUAL_LEAVE);
        int paidQuota = settings.getDefaultPaidLeaveQuota();
        int annualQuota = settings.getDefaultAnnualLeaveQuota();
        int paidPending = Math.max(0, paidQuota - paidTaken);
        int annualPending = Math.max(0, annualQuota - annualTaken);
        return new LeaveMarkResponse(
                rec.getId(),
                subject.getId(),
                subject.getWorkerCode(),
                subject.getFullName(),
                rec.getLeaveDate(),
                settings.getTimezoneId(),
                rec.getLeaveType(),
                rec.getLeaveType().getDisplayLabel(),
                "APPROVED",
                replay,
                paidQuota,
                paidTaken,
                paidPending,
                annualQuota,
                annualTaken,
                annualPending,
                paidPending + annualPending
        );
    }

    private BiometricVerificationToken validateTokenForUse(String rawVerificationToken) {
        if (rawVerificationToken == null || rawVerificationToken.isBlank()) {
            throw new ValidationException("VERIFICATION_TOKEN_REQUIRED", "Face verification is required before marking leave.");
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

    private static boolean isEmployedOn(Worker worker, LocalDate date) {
        if (worker.getEmploymentStartDate() != null && date.isBefore(worker.getEmploymentStartDate())) {
            return false;
        }
        return worker.getEmploymentEndDate() == null || !date.isAfter(worker.getEmploymentEndDate());
    }

    private AttendanceSettings loadSettings() {
        return settingsRepository.findTopByOrderByEffectiveFromUtcDesc()
                .orElseThrow(() -> new IllegalStateException("Attendance settings not found."));
    }

    public record LeaveMarkResponse(
            UUID id,
            UUID employeeId,
            String employeeCode,
            String employeeName,
            LocalDate leaveDate,
            String timezoneId,
            LeaveType leaveType,
            String leaveTypeLabel,
            String status,
            boolean idempotentReplay,
            int paidLeaveQuota,
            int paidLeaveTaken,
            int paidLeavePending,
            int annualLeaveQuota,
            int annualLeaveTaken,
            int annualLeavePending,
            int totalLeavePending
    ) {
    }

    public record EmployeeLeaveBalanceRow(
            UUID workerId,
            String workerCode,
            String fullName,
            String teamName,
            WorkerStatus status,
            int monthWorkingDays,
            int applicableWorkingDays,
            int monthLeaveDays,
            int paidLeaveQuota,
            int paidLeaveTaken,
            int paidLeavePending,
            int annualLeaveQuota,
            int annualLeaveTaken,
            int annualLeavePending,
            int totalApprovedTaken,
            int totalPendingRemaining
    ) {
    }

    public record LeaveEntryDto(
            UUID id,
            UUID workerId,
            String workerCode,
            String fullName,
            String teamName,
            LocalDate leaveDate,
            LeaveType leaveType,
            String leaveTypeLabel,
            LeaveSource source,
            String sourceLabel,
            String status,
            String notes,
            Instant createdAt
    ) {
    }

    public record AdminLeaveOverviewResponse(
            String month,
            String workplaceName,
            String timezoneId,
            int totalWorkingDays,
            int paidLeaveQuota,
            int annualLeaveQuota,
            boolean autoMarkLeaveAfterCutoff,
            int totalApprovedTaken,
            int totalPendingRemaining,
            int totalPaidTaken,
            int totalAnnualTaken,
            List<String> teams,
            List<EmployeeLeaveBalanceRow> employees,
            List<LeaveEntryDto> entries
    ) {
    }
}
