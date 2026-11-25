package com.example.attendance.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    private UUID id;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 30)
    private AuditActorType actorType;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "subject_worker_id")
    private UUID subjectWorkerId;

    @Column(name = "event_timestamp_utc", nullable = false)
    private Instant eventTimestampUtc;

    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    protected AuditEvent() {
    }

    public AuditEvent(UUID id, String eventType, AuditActorType actorType, UUID actorId,
                      UUID subjectWorkerId, Instant eventTimestampUtc, String metadataJson, String correlationId) {
        this.id = id;
        this.eventType = eventType;
        this.actorType = actorType;
        this.actorId = actorId;
        this.subjectWorkerId = subjectWorkerId;
        this.eventTimestampUtc = eventTimestampUtc;
        this.metadataJson = metadataJson;
        this.correlationId = correlationId;
    }

    public UUID getId() {
        return id;
    }

    public String getEventType() {
        return eventType;
    }

    public AuditActorType getActorType() {
        return actorType;
    }

    public UUID getActorId() {
        return actorId;
    }

    public UUID getSubjectWorkerId() {
        return subjectWorkerId;
    }

    public Instant getEventTimestampUtc() {
        return eventTimestampUtc;
    }

    public String getMetadataJson() {
        return metadataJson;
    }

    public String getCorrelationId() {
        return correlationId;
    }
}
