package com.example.attendance.biometrics;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "biometric_verification_tokens")
public class BiometricVerificationToken {

    @Id
    private UUID id;

    @Column(name = "token_hash", nullable = false, unique = true, length = 128)
    private String tokenHash;

    @Column(name = "actor_worker_id", nullable = false)
    private UUID actorWorkerId;

    @Column(name = "subject_worker_id", nullable = false)
    private UUID subjectWorkerId;

    @Column(name = "verification_mode", nullable = false, length = 20)
    private String verificationMode;

    @Column(name = "match_confidence", nullable = false)
    private double matchConfidence;

    @Column(name = "mock_mode", nullable = false)
    private boolean mockMode;

    @Column(name = "expires_at_utc", nullable = false)
    private Instant expiresAtUtc;

    @Column(name = "consumed_at_utc")
    private Instant consumedAtUtc;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected BiometricVerificationToken() {
    }

    public BiometricVerificationToken(UUID id, String tokenHash, UUID actorWorkerId, UUID subjectWorkerId,
                                      String verificationMode, double matchConfidence, boolean mockMode,
                                      Instant expiresAtUtc, Instant createdAt) {
        this.id = id;
        this.tokenHash = tokenHash;
        this.actorWorkerId = actorWorkerId;
        this.subjectWorkerId = subjectWorkerId;
        this.verificationMode = verificationMode;
        this.matchConfidence = matchConfidence;
        this.mockMode = mockMode;
        this.expiresAtUtc = expiresAtUtc;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public UUID getActorWorkerId() {
        return actorWorkerId;
    }

    public UUID getSubjectWorkerId() {
        return subjectWorkerId;
    }

    public String getVerificationMode() {
        return verificationMode;
    }

    public double getMatchConfidence() {
        return matchConfidence;
    }

    public boolean isMockMode() {
        return mockMode;
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
}
