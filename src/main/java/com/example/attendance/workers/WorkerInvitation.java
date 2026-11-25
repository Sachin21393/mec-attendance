package com.example.attendance.workers;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "worker_invitations")
public class WorkerInvitation {

    @Id
    private UUID id;

    @Column(name = "worker_id", nullable = false)
    private UUID workerId;

    @Column(name = "code_hash", nullable = false, unique = true, length = 128)
    private String codeHash;

    @Column(name = "code_hint", nullable = false, length = 32)
    private String codeHint;

    @Column(name = "expires_at_utc", nullable = false)
    private Instant expiresAtUtc;

    @Column(name = "consumed_at_utc")
    private Instant consumedAtUtc;

    @Column(name = "created_by_admin_id")
    private UUID createdByAdminId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WorkerInvitation() {
    }

    public WorkerInvitation(UUID id, UUID workerId, String codeHash, String codeHint,
                            Instant expiresAtUtc, UUID createdByAdminId, Instant createdAt) {
        this.id = id;
        this.workerId = workerId;
        this.codeHash = codeHash;
        this.codeHint = codeHint;
        this.expiresAtUtc = expiresAtUtc;
        this.createdByAdminId = createdByAdminId;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkerId() {
        return workerId;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public String getCodeHint() {
        return codeHint;
    }

    public Instant getExpiresAtUtc() {
        return expiresAtUtc;
    }

    public Instant getConsumedAtUtc() {
        return consumedAtUtc;
    }

    public void setConsumedAtUtc(Instant consumedAtUtc) {
        this.consumedAtUtc = consumedAtUtc;
    }

    public UUID getCreatedByAdminId() {
        return createdByAdminId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
