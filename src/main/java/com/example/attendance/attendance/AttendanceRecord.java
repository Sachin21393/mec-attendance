package com.example.attendance.attendance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "attendance_records")
public class AttendanceRecord {

    @Id
    private UUID id;

    @Column(name = "subject_worker_id", nullable = false)
    private UUID subjectWorkerId;

    @Column(name = "actor_worker_id")
    private UUID actorWorkerId;

    @Column(name = "attendance_date", nullable = false)
    private LocalDate attendanceDate;

    @Column(name = "check_in_at_utc", nullable = false)
    private Instant checkInAtUtc;

    @Enumerated(EnumType.STRING)
    @Column(name = "attendance_status", nullable = false, length = 30)
    private AttendanceStatus attendanceStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "attendance_method", nullable = false, length = 30)
    private AttendanceMethod attendanceMethod;

    @Column(name = "idempotency_key", unique = true, length = 128)
    private String idempotencyKey;

    @Column(name = "match_confidence")
    private Double matchConfidence;

    @Column(name = "check_out_at_utc")
    private Instant checkOutAtUtc;

    @Enumerated(EnumType.STRING)
    @Column(name = "logout_status", length = 30)
    private LogoutStatus logoutStatus;

    @Column(name = "logout_idempotency_key", unique = true, length = 128)
    private String logoutIdempotencyKey;

    @Column(name = "logout_match_confidence")
    private Double logoutMatchConfidence;

    @Column(name = "device_session_id")
    private UUID deviceSessionId;

    @Column(length = 500)
    private String notes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AttendanceRecord() {
    }

    public AttendanceRecord(UUID id, UUID subjectWorkerId, UUID actorWorkerId, LocalDate attendanceDate,
                            Instant checkInAtUtc, AttendanceStatus attendanceStatus, AttendanceMethod attendanceMethod,
                            String idempotencyKey, Double matchConfidence, UUID deviceSessionId, String notes, Instant now) {
        this.id = id;
        this.subjectWorkerId = subjectWorkerId;
        this.actorWorkerId = actorWorkerId;
        this.attendanceDate = attendanceDate;
        this.checkInAtUtc = checkInAtUtc;
        this.attendanceStatus = attendanceStatus;
        this.attendanceMethod = attendanceMethod;
        this.idempotencyKey = idempotencyKey;
        this.matchConfidence = matchConfidence;
        this.deviceSessionId = deviceSessionId;
        this.notes = notes;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSubjectWorkerId() {
        return subjectWorkerId;
    }

    public UUID getActorWorkerId() {
        return actorWorkerId;
    }

    public LocalDate getAttendanceDate() {
        return attendanceDate;
    }

    public Instant getCheckInAtUtc() {
        return checkInAtUtc;
    }

    public void setCheckInAtUtc(Instant checkInAtUtc) {
        this.checkInAtUtc = checkInAtUtc;
    }

    public AttendanceStatus getAttendanceStatus() {
        return attendanceStatus;
    }

    public void setAttendanceStatus(AttendanceStatus attendanceStatus) {
        this.attendanceStatus = attendanceStatus;
    }

    public AttendanceMethod getAttendanceMethod() {
        return attendanceMethod;
    }

    public void setAttendanceMethod(AttendanceMethod attendanceMethod) {
        this.attendanceMethod = attendanceMethod;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Double getMatchConfidence() {
        return matchConfidence;
    }

    public Instant getCheckOutAtUtc() {
        return checkOutAtUtc;
    }

    public void setCheckOutAtUtc(Instant checkOutAtUtc) {
        this.checkOutAtUtc = checkOutAtUtc;
    }

    public LogoutStatus getLogoutStatus() {
        return logoutStatus;
    }

    public void setLogoutStatus(LogoutStatus logoutStatus) {
        this.logoutStatus = logoutStatus;
    }

    public String getLogoutIdempotencyKey() {
        return logoutIdempotencyKey;
    }

    public void setLogoutIdempotencyKey(String logoutIdempotencyKey) {
        this.logoutIdempotencyKey = logoutIdempotencyKey;
    }

    public Double getLogoutMatchConfidence() {
        return logoutMatchConfidence;
    }

    public void setLogoutMatchConfidence(Double logoutMatchConfidence) {
        this.logoutMatchConfidence = logoutMatchConfidence;
    }

    public UUID getDeviceSessionId() {
        return deviceSessionId;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
