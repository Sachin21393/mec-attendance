package com.example.attendance.biometrics;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "worker_biometrics")
public class WorkerBiometric {

    @Id
    private UUID id;

    @Column(name = "worker_id", nullable = false, unique = true)
    private UUID workerId;

    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(name = "encrypted_embedding", nullable = false)
    private byte[] encryptedEmbedding;

    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(name = "encryption_iv", nullable = false)
    private byte[] encryptionIv;

    @Column(name = "key_version", nullable = false, length = 32)
    private String keyVersion;

    @Column(name = "embedding_model", nullable = false, length = 120)
    private String embeddingModel;

    @Column(name = "model_version", nullable = false, length = 64)
    private String modelVersion;

    @Column(name = "embedding_dimension", nullable = false)
    private int embeddingDimension;

    @Column(name = "consent_version", nullable = false, length = 40)
    private String consentVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WorkerBiometric() {
    }

    public WorkerBiometric(UUID id, UUID workerId, byte[] encryptedEmbedding, byte[] encryptionIv,
                           String keyVersion, String embeddingModel, String modelVersion,
                           int embeddingDimension, String consentVersion, Instant now) {
        this.id = id;
        this.workerId = workerId;
        this.encryptedEmbedding = encryptedEmbedding;
        this.encryptionIv = encryptionIv;
        this.keyVersion = keyVersion;
        this.embeddingModel = embeddingModel;
        this.modelVersion = modelVersion;
        this.embeddingDimension = embeddingDimension;
        this.consentVersion = consentVersion;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkerId() {
        return workerId;
    }

    public byte[] getEncryptedEmbedding() {
        return encryptedEmbedding;
    }

    public byte[] getEncryptionIv() {
        return encryptionIv;
    }

    public String getKeyVersion() {
        return keyVersion;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public int getEmbeddingDimension() {
        return embeddingDimension;
    }

    public String getConsentVersion() {
        return consentVersion;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
