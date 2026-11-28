package com.example.attendance.attendance;

import com.example.attendance.audit.AuditActorType;
import com.example.attendance.audit.AuditService;
import com.example.attendance.auth.AdminPrincipal;
import com.example.attendance.common.ConflictException;
import com.example.attendance.common.ResourceNotFoundException;
import com.example.attendance.settings.AttendanceSettings;
import com.example.attendance.settings.AttendanceSettingsRepository;
import com.example.attendance.workers.Worker;
import com.example.attendance.workers.WorkerRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/attendance")
public class AdminAttendanceCorrectionController {

    private final AttendanceRecordRepository attendanceRepository;
    private final AttendanceCorrectionRepository correctionRepository;
    private final WorkerRepository workerRepository;
    private final AttendanceSettingsRepository settingsRepository;
    private final AuditService auditService;

    public AdminAttendanceCorrectionController(
            AttendanceRecordRepository attendanceRepository,
            AttendanceCorrectionRepository correctionRepository,
            WorkerRepository workerRepository,
            AttendanceSettingsRepository settingsRepository,
            AuditService auditService
    ) {
        this.attendanceRepository = attendanceRepository;
        this.correctionRepository = correctionRepository;
        this.workerRepository = workerRepository;
        this.settingsRepository = settingsRepository;
        this.auditService = auditService;
    }

    @PostMapping("/manual")
    @Transactional
    public MonthlyAttendanceQueryService.AttendanceDetailDto recordManualException(
            @Valid @RequestBody ManualAttendanceRequest request,
            @AuthenticationPrincipal AdminPrincipal admin
    ) {
        Worker worker = workerRepository.findById(request.workerId())
                .orElseThrow(() -> new ResourceNotFoundException("WORKER_NOT_FOUND", "Employee not found."));

        if (attendanceRepository.findBySubjectWorkerIdAndAttendanceDate(worker.getId(), request.attendanceDate()).isPresent()) {
            throw new ConflictException(
                    "RECORD_ALREADY_EXISTS",
                    "An attendance record already exists for " + worker.getFullName() + " on " + request.attendanceDate() + ". Use correction instead."
            );
        }

        AttendanceSettings settings = settingsRepository.findTopByOrderByEffectiveFromUtcDesc()
                .orElseThrow(() -> new IllegalStateException("Attendance settings not found."));
        ZoneId zone = ZoneId.of(settings.getTimezoneId());
        LocalTime localTime = request.checkInLocalTime() != null ? request.checkInLocalTime() : settings.getDefaultShiftStartLocal();
        Instant checkInUtc = LocalDateTime.of(request.attendanceDate(), localTime).atZone(zone).toInstant();
        Instant now = Instant.now();

        AttendanceRecord record = new AttendanceRecord(
                UUID.randomUUID(),
                worker.getId(),
                null,
                request.attendanceDate(),
                checkInUtc,
                request.status(),
                AttendanceMethod.ADMIN_MANUAL,
                "manual-" + UUID.randomUUID(),
                null,
                null,
                request.reason().trim(),
                now
        );
        attendanceRepository.save(record);

        correctionRepository.save(new AttendanceCorrection(
                UUID.randomUUID(),
                record.getId(),
                worker.getId(),
                request.attendanceDate(),
                "ABSENT_OR_UNMARKED",
                request.status().name(),
                null,
                checkInUtc,
                request.reason().trim(),
                admin != null ? admin.adminUserId() : null,
                now
        ));

        auditService.record(
                "ATTENDANCE_MANUAL_EXCEPTION",
                AuditActorType.ADMIN,
                admin != null ? admin.adminUserId() : null,
                worker.getId(),
                Map.of(
                        "attendanceDate", request.attendanceDate().toString(),
                        "status", request.status().name(),
                        "reason", request.reason().trim()
                )
        );

        return new MonthlyAttendanceQueryService.AttendanceDetailDto(
                record.getId(),
                worker.getId(),
                worker.getWorkerCode(),
                worker.getFullName(),
                worker.getTeamName(),
                null,
                null,
                "Admin",
                record.getAttendanceDate(),
                record.getCheckInAtUtc(),
                localTime.toString(),
                zone.getId(),
                record.getAttendanceStatus(),
                record.getAttendanceMethod(),
                record.getNotes(),
                record.getCheckOutAtUtc(),
                null,
                record.getLogoutStatus(),
                record.getLogoutStatus() != null ? record.getLogoutStatus().getDisplayLabel() : null,
                java.util.List.of(),
                java.util.List.of()
        );
    }

    @PostMapping("/{id}/correct")
    @Transactional
    public Map<String, Object> correctAttendance(
            @PathVariable UUID id,
            @Valid @RequestBody CorrectAttendanceRequest request,
            @AuthenticationPrincipal AdminPrincipal admin
    ) {
        AttendanceRecord record = attendanceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ATTENDANCE_NOT_FOUND", "Attendance record not found."));

        Instant now = Instant.now();
        String previousStatus = record.getAttendanceStatus().name();
        Instant previousCheckIn = record.getCheckInAtUtc();

        record.setAttendanceStatus(request.newStatus());
        if (request.newCheckInAtUtc() != null) {
            record.setCheckInAtUtc(request.newCheckInAtUtc());
        }
        record.setNotes(request.reason().trim());
        record.setUpdatedAt(now);
        attendanceRepository.save(record);

        AttendanceCorrection correction = new AttendanceCorrection(
                UUID.randomUUID(),
                record.getId(),
                record.getSubjectWorkerId(),
                record.getAttendanceDate(),
                previousStatus,
                request.newStatus().name(),
                previousCheckIn,
                record.getCheckInAtUtc(),
                request.reason().trim(),
                admin != null ? admin.adminUserId() : null,
                now
        );
        correctionRepository.save(correction);

        auditService.record(
                "ATTENDANCE_CORRECTED",
                AuditActorType.ADMIN,
                admin != null ? admin.adminUserId() : null,
                record.getSubjectWorkerId(),
                Map.of(
                        "recordId", record.getId().toString(),
                        "attendanceDate", record.getAttendanceDate().toString(),
                        "previousStatus", previousStatus,
                        "newStatus", request.newStatus().name(),
                        "reason", request.reason().trim()
                )
        );

        return Map.of(
                "recordId", record.getId(),
                "correctionId", correction.getId(),
                "previousStatus", previousStatus,
                "newStatus", request.newStatus().name()
        );
    }

    public record ManualAttendanceRequest(
            @NotNull(message = "Employee ID is required") UUID workerId,
            @NotNull(message = "Attendance date is required") LocalDate attendanceDate,
            @NotNull(message = "Attendance status is required") AttendanceStatus status,
            LocalTime checkInLocalTime,
            @NotBlank(message = "Reason is required") @Size(max = 500) String reason
    ) {
    }

    public record CorrectAttendanceRequest(
            @NotNull(message = "New status is required") AttendanceStatus newStatus,
            Instant newCheckInAtUtc,
            @NotBlank(message = "Correction reason is required") @Size(max = 500) String reason
    ) {
    }
}
