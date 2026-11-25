package com.example.attendance.workers;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "worker_sessions")
public class WorkerSession {

    @Id
    private UUID id;

    @Column(name = "worker_id", nullable = false)
    private UUID workerId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 128)
    private String tokenHash;

    @Column(name = "device_label", length = 160)
    private String deviceLabel;

    @Column(name = "expires_at_utc", nullable = false)
    private Instant expiresAtUtc;

    @Column(name = "revoked_at_utc")
    private Instant revokedAtUtc;

    @Column(name = "last_used_at_utc")
    private Instant lastUsedAtUtc;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WorkerSession() {
    }

    public WorkerSession(UUID id, UUID workerId, String tokenHash, String deviceLabel, Instant expiresAtUtc, Instant now) {
        this.id = id;
        this.workerId = workerId;
        this.tokenHash = tokenHash;
        this.deviceLabel = deviceLabel;
        this.expiresAtUtc = expiresAtUtc;
        this.lastUsedAtUtc = now;
        this.createdAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkerId() {
        return workerId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public String getDeviceLabel() {
        return deviceLabel;
    }

    public Instant getExpiresAtUtc() {
        return expiresAtUtc;
    }

    public Instant getRevokedAtUtc() {
        return revokedAtUtc;
    }

    public void setRevokedAtUtc(Instant revokedAtUtc) {
        this.revokedAtUtc = revokedAtUtc;
    }

    public Instant getLastUsedAtUtc() {
        return lastUsedAtUtc;
    }

    public void setLastUsedAtUtc(Instant lastUsedAtUtc) {
        this.lastUsedAtUtc = lastUsedAtUtc;
    }

    public boolean isActive(Instant now) {
        return revokedAtUtc == null && expiresAtUtc.isAfter(now);
    }
}
