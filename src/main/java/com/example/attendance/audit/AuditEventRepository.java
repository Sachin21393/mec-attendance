package com.example.attendance.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {
    Page<AuditEvent> findAllByOrderByEventTimestampUtcDesc(Pageable pageable);

    @Query("""
            SELECT a FROM AuditEvent a
            WHERE (:eventType IS NULL OR a.eventType = :eventType)
              AND (:actorId IS NULL OR a.actorId = :actorId)
              AND (:subjectWorkerId IS NULL OR a.subjectWorkerId = :subjectWorkerId)
              AND (:fromTs IS NULL OR a.eventTimestampUtc >= :fromTs)
              AND (:toTs IS NULL OR a.eventTimestampUtc <= :toTs)
            ORDER BY a.eventTimestampUtc DESC
            """)
    Page<AuditEvent> search(
            @Param("eventType") String eventType,
            @Param("actorId") UUID actorId,
            @Param("subjectWorkerId") UUID subjectWorkerId,
            @Param("fromTs") Instant fromTs,
            @Param("toTs") Instant toTs,
            Pageable pageable
    );
}
