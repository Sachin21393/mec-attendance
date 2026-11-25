package com.example.attendance.attendance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "attendance_corrections")
public class AttendanceCorrection {

    @Id
    private UUID id;

    @Column(name = "attendance_record_id")
    private UUID attendanceRecordId;

    @Column(name = "subject_worker_id", nullable = false)
    private UUID subjectWorkerId;

    @Column(name = "attendance_date", nullable = false)
    private LocalDate attendanceDate;

    @Column(name = "previous_status", length = 30)
    private String previousStatus;

    @Column(name = "new_status", nullable = false, length = 30)
    private String newStatus;

    @Column(name = "previous_check_in_at_utc")
    private Instant previousCheckInAtUtc;

    @Column(name = "new_check_in_at_utc")
    private Instant newCheckInAtUtc;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(name = "corrected_by_admin_id")
    private UUID correctedByAdminId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AttendanceCorrection() {
    }

    public AttendanceCorrection(
            UUID id,
            UUID attendanceRecordId,
            UUID subjectWorkerId,
            LocalDate attendanceDate,
            String previousStatus,
            String newStatus,
            Instant previousCheckInAtUtc,
            Instant newCheckInAtUtc,
            String reason,
            UUID correctedByAdminId,
            Instant createdAt
    ) {
        this.id = id;
        this.attendanceRecordId = attendanceRecordId;
        this.subjectWorkerId = subjectWorkerId;
        this.attendanceDate = attendanceDate;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
        this.previousCheckInAtUtc = previousCheckInAtUtc;
        this.newCheckInAtUtc = newCheckInAtUtc;
        this.reason = reason;
        this.correctedByAdminId = correctedByAdminId;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAttendanceRecordId() {
        return attendanceRecordId;
    }

    public UUID getSubjectWorkerId() {
        return subjectWorkerId;
    }

    public LocalDate getAttendanceDate() {
        return attendanceDate;
    }

    public String getPreviousStatus() {
        return previousStatus;
    }

    public String getNewStatus() {
        return newStatus;
    }

    public Instant getPreviousCheckInAtUtc() {
        return previousCheckInAtUtc;
    }

    public Instant getNewCheckInAtUtc() {
        return newCheckInAtUtc;
    }

    public String getReason() {
        return reason;
    }

    public UUID getCorrectedByAdminId() {
        return correctedByAdminId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
