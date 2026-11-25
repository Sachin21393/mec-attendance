package com.example.attendance.workers;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "consent_records")
public class ConsentRecord {

    @Id
    private UUID id;

    @Column(name = "worker_id", nullable = false)
    private UUID workerId;

    @Column(name = "consent_version", nullable = false, length = 40)
    private String consentVersion;

    @Column(name = "consent_given", nullable = false)
    private boolean consentGiven;

    @Column(name = "notice_summary", nullable = false, length = 500)
    private String noticeSummary;

    @Column(name = "ip_address_hash", length = 128)
    private String ipAddressHash;

    @Column(name = "recorded_at_utc", nullable = false)
    private Instant recordedAtUtc;

    protected ConsentRecord() {
    }

    public ConsentRecord(UUID id, UUID workerId, String consentVersion, boolean consentGiven,
                         String noticeSummary, String ipAddressHash, Instant recordedAtUtc) {
        this.id = id;
        this.workerId = workerId;
        this.consentVersion = consentVersion;
        this.consentGiven = consentGiven;
        this.noticeSummary = noticeSummary;
        this.ipAddressHash = ipAddressHash;
        this.recordedAtUtc = recordedAtUtc;
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkerId() {
        return workerId;
    }

    public String getConsentVersion() {
        return consentVersion;
    }

    public boolean isConsentGiven() {
        return consentGiven;
    }

    public String getNoticeSummary() {
        return noticeSummary;
    }

    public String getIpAddressHash() {
        return ipAddressHash;
    }

    public Instant getRecordedAtUtc() {
        return recordedAtUtc;
    }
}
