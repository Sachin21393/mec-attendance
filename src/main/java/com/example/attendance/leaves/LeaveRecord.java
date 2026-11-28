package com.example.attendance.leaves;

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
@Table(name = "leave_records")
public class LeaveRecord {

    @Id
    private UUID id;

    @Column(name = "worker_id", nullable = false)
    private UUID workerId;

    @Column(name = "leave_date", nullable = false)
    private LocalDate leaveDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "leave_type", nullable = false, length = 30)
    private LeaveType leaveType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private LeaveSource source;

    @Column(length = 500)
    private String notes;

    @Column(name = "idempotency_key", unique = true, length = 128)
    private String idempotencyKey;

    @Column(name = "match_confidence")
    private Double matchConfidence;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LeaveRecord() {
    }

    public LeaveRecord(
            UUID id,
            UUID workerId,
            LocalDate leaveDate,
            LeaveType leaveType,
            LeaveSource source,
            String notes,
            String idempotencyKey,
            Double matchConfidence,
            Instant now
    ) {
        this.id = id;
        this.workerId = workerId;
        this.leaveDate = leaveDate;
        this.leaveType = leaveType;
        this.source = source;
        this.notes = notes;
        this.idempotencyKey = idempotencyKey;
        this.matchConfidence = matchConfidence;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkerId() {
        return workerId;
    }

    public LocalDate getLeaveDate() {
        return leaveDate;
    }

    public LeaveType getLeaveType() {
        return leaveType;
    }

    public void setLeaveType(LeaveType leaveType) {
        this.leaveType = leaveType;
    }

    public LeaveSource getSource() {
        return source;
    }

    public void setSource(LeaveSource source) {
        this.source = source;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Double getMatchConfidence() {
        return matchConfidence;
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
