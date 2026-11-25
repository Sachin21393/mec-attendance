package com.example.attendance.audit;

import com.example.attendance.common.ValidationException;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/audit-events")
public class AdminAuditController {

    private final AuditEventRepository auditEventRepository;

    public AdminAuditController(AuditEventRepository auditEventRepository) {
        this.auditEventRepository = auditEventRepository;
    }

    @GetMapping
    public Page<AuditEvent> listAuditEvents(
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) UUID subjectWorkerId,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        String cleanType = (eventType == null || eventType.isBlank()) ? null : eventType.trim();
        Instant fromTs = parseDateStart(fromDate);
        Instant toTs = parseDateEnd(toDate);
        PageRequest pageable = PageRequest.of(
                Math.max(page, 0),
                Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.DESC, "eventTimestampUtc")
        );
        Specification<AuditEvent> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (cleanType != null) {
                predicates.add(cb.equal(root.get("eventType"), cleanType));
            }
            if (actorId != null) {
                predicates.add(cb.equal(root.get("actorId"), actorId));
            }
            if (subjectWorkerId != null) {
                predicates.add(cb.equal(root.get("subjectWorkerId"), subjectWorkerId));
            }
            if (fromTs != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("eventTimestampUtc"), fromTs));
            }
            if (toTs != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("eventTimestampUtc"), toTs));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return auditEventRepository.findAll(spec, pageable);
    }

    private static Instant parseDateStart(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(dateStr.trim()).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException e) {
            throw new ValidationException("INVALID_DATE", "Date filter must use YYYY-MM-DD format.");
        }
    }

    private static Instant parseDateEnd(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(dateStr.trim()).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1);
        } catch (DateTimeParseException e) {
            throw new ValidationException("INVALID_DATE", "Date filter must use YYYY-MM-DD format.");
        }
    }
}
