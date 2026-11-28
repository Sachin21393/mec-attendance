package com.example.attendance.workers;

import com.example.attendance.audit.AuditActorType;
import com.example.attendance.audit.AuditService;
import com.example.attendance.auth.AdminPrincipal;
import com.example.attendance.auth.AdminUser;
import com.example.attendance.auth.AdminUserRepository;
import com.example.attendance.common.ResourceNotFoundException;
import com.example.attendance.common.UnauthorizedException;
import com.example.attendance.common.ValidationException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class AdminWorkerDeletionService {

    private final AdminUserRepository adminUserRepository;
    private final WorkerRepository workerRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    @PersistenceContext
    private EntityManager entityManager;

    public AdminWorkerDeletionService(
            AdminUserRepository adminUserRepository,
            WorkerRepository workerRepository,
            PasswordEncoder passwordEncoder,
            AuditService auditService
    ) {
        this.adminUserRepository = adminUserRepository;
        this.workerRepository = workerRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    @Transactional
    public DeleteWorkersOutcome deleteSingle(
            AdminPrincipal principal,
            UUID workerId,
            String adminPassword,
            String reason
    ) {
        return deleteWorkers(principal, List.of(workerId), adminPassword, reason, false);
    }

    @Transactional
    public DeleteWorkersOutcome deleteBulk(
            AdminPrincipal principal,
            List<UUID> workerIds,
            String adminPassword,
            String reason
    ) {
        return deleteWorkers(principal, workerIds, adminPassword, reason, true);
    }

    private DeleteWorkersOutcome deleteWorkers(
            AdminPrincipal principal,
            List<UUID> rawWorkerIds,
            String adminPassword,
            String reason,
            boolean bulk
    ) {
        if (principal == null) {
            throw new UnauthorizedException("UNAUTHORIZED", "Administrator session is required.");
        }
        AdminUser admin = adminUserRepository.findById(principal.adminUserId())
                .filter(AdminUser::isEnabled)
                .orElseThrow(() -> new UnauthorizedException("UNAUTHORIZED", "Administrator account not found or disabled."));

        if (adminPassword == null || adminPassword.isEmpty()
                || !passwordEncoder.matches(adminPassword, admin.getPasswordHash())) {
            throw new UnauthorizedException("INVALID_ADMIN_PASSWORD", "Incorrect administrator password.");
        }

        if (rawWorkerIds == null || rawWorkerIds.isEmpty()) {
            throw new ValidationException("WORKERS_REQUIRED", "Select at least one employee to delete.");
        }
        Set<UUID> uniqueIds = new LinkedHashSet<>(rawWorkerIds);

        String cleanReason = reason == null ? "" : reason.trim();
        if (bulk && cleanReason.isEmpty()) {
            throw new ValidationException("DELETE_REASON_REQUIRED", "Please enter a reason for bulk deletion.");
        }
        String effectiveReason = cleanReason.isEmpty() ? "Removed by administrator" : cleanReason;

        List<Worker> targets = workerRepository.findAllById(uniqueIds);
        if (targets.size() != uniqueIds.size()) {
            throw new ResourceNotFoundException("WORKER_NOT_FOUND", "One or more selected employees were not found.");
        }

        List<String> deletedCodes = targets.stream().map(Worker::getWorkerCode).sorted().toList();
        UUID singleSubjectId = targets.size() == 1 ? targets.get(0).getId() : null;

        entityManager.flush();

        // Remove dependent rows in FK-safe order; preserve other workers' proxy records by clearing actor reference
        entityManager.createQuery("""
                DELETE FROM AttendanceCorrection c
                WHERE c.subjectWorkerId IN :ids
                   OR c.attendanceRecordId IN (SELECT r.id FROM AttendanceRecord r WHERE r.subjectWorkerId IN :ids)
                """)
                .setParameter("ids", uniqueIds)
                .executeUpdate();

        entityManager.createQuery("""
                UPDATE AttendanceRecord r SET r.actorWorkerId = NULL
                WHERE r.actorWorkerId IN :ids AND r.subjectWorkerId NOT IN :ids
                """)
                .setParameter("ids", uniqueIds)
                .executeUpdate();

        entityManager.createQuery("DELETE FROM AttendanceRecord r WHERE r.subjectWorkerId IN :ids")
                .setParameter("ids", uniqueIds)
                .executeUpdate();

        entityManager.createQuery("""
                DELETE FROM BiometricVerificationToken t
                WHERE t.actorWorkerId IN :ids OR t.subjectWorkerId IN :ids
                """)
                .setParameter("ids", uniqueIds)
                .executeUpdate();

        entityManager.createQuery("DELETE FROM ConsentRecord c WHERE c.workerId IN :ids")
                .setParameter("ids", uniqueIds)
                .executeUpdate();

        entityManager.createQuery("DELETE FROM WorkerBiometric b WHERE b.workerId IN :ids")
                .setParameter("ids", uniqueIds)
                .executeUpdate();

        entityManager.createQuery("DELETE FROM WorkerInvitation i WHERE i.workerId IN :ids")
                .setParameter("ids", uniqueIds)
                .executeUpdate();

        entityManager.createQuery("DELETE FROM WorkerSession s WHERE s.workerId IN :ids")
                .setParameter("ids", uniqueIds)
                .executeUpdate();

        entityManager.createQuery("DELETE FROM Worker w WHERE w.id IN :ids")
                .setParameter("ids", uniqueIds)
                .executeUpdate();

        entityManager.flush();
        entityManager.clear();

        auditService.record(
                bulk ? "WORKERS_BULK_DELETED" : "WORKER_DELETED",
                AuditActorType.ADMIN,
                admin.getId(),
                singleSubjectId,
                Map.of(
                        "deletedCount", deletedCodes.size(),
                        "workerCodes", deletedCodes,
                        "reason", effectiveReason
                )
        );

        return new DeleteWorkersOutcome(deletedCodes.size(), deletedCodes);
    }

    public record DeleteWorkersOutcome(int deletedCount, List<String> deletedWorkerCodes) {
    }
}
