package com.example.attendance.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "admin_sessions")
public class AdminSession {

    @Id
    private UUID id;

    @Column(name = "admin_user_id", nullable = false)
    private UUID adminUserId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 128)
    private String tokenHash;

    @Column(name = "expires_at_utc", nullable = false)
    private Instant expiresAtUtc;

    @Column(name = "revoked_at_utc")
    private Instant revokedAtUtc;

    @Column(name = "last_used_at_utc")
    private Instant lastUsedAtUtc;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AdminSession() {
    }

    public AdminSession(UUID id, UUID adminUserId, String tokenHash, Instant expiresAtUtc, Instant now) {
        this.id = id;
        this.adminUserId = adminUserId;
        this.tokenHash = tokenHash;
        this.expiresAtUtc = expiresAtUtc;
        this.lastUsedAtUtc = now;
        this.createdAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAdminUserId() {
        return adminUserId;
    }

    public String getTokenHash() {
        return tokenHash;
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
