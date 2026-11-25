package com.example.attendance.audit;

import com.example.attendance.common.CorrelationIdFilter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class AuditService {

    private final AuditEventRepository auditEventRepository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditEventRepository auditEventRepository, ObjectMapper objectMapper) {
        this.auditEventRepository = auditEventRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void record(String eventType, AuditActorType actorType, UUID actorId, UUID subjectWorkerId, Map<String, Object> metadata) {
        String json = serializeMetadata(metadata);
        AuditEvent event = new AuditEvent(
                UUID.randomUUID(),
                eventType,
                actorType,
                actorId,
                subjectWorkerId,
                Instant.now(),
                json,
                CorrelationIdFilter.currentOrGenerate()
        );
        auditEventRepository.save(event);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIndependent(String eventType, AuditActorType actorType, UUID actorId, UUID subjectWorkerId, Map<String, Object> metadata) {
        record(eventType, actorType, actorId, subjectWorkerId, metadata);
    }

    private String serializeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
