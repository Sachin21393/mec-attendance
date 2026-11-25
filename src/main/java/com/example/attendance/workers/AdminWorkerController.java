package com.example.attendance.workers;

import com.example.attendance.audit.AuditActorType;
import com.example.attendance.audit.AuditService;
import com.example.attendance.auth.AdminPrincipal;
import com.example.attendance.auth.TokenHashUtil;
import com.example.attendance.biometrics.WorkerBiometricRepository;
import com.example.attendance.common.ConflictException;
import com.example.attendance.common.ResourceNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/workers")
public class AdminWorkerController {

    private final WorkerRepository workerRepository;
    private final WorkerInvitationRepository invitationRepository;
    private final WorkerBiometricRepository biometricRepository;
    private final WorkerSessionRepository sessionRepository;
    private final AuditService auditService;

    public AdminWorkerController(
            WorkerRepository workerRepository,
            WorkerInvitationRepository invitationRepository,
            WorkerBiometricRepository biometricRepository,
            WorkerSessionRepository sessionRepository,
            AuditService auditService
    ) {
        this.workerRepository = workerRepository;
        this.invitationRepository = invitationRepository;
        this.biometricRepository = biometricRepository;
        this.sessionRepository = sessionRepository;
        this.auditService = auditService;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public Page<WorkerSummaryDto> listWorkers(
            @RequestParam(defaultValue = "") String query,
            @RequestParam(required = false) WorkerStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        String q = query == null ? "" : query.trim().toLowerCase();
        List<Worker> filtered = workerRepository.findAll(Sort.by("workerCode").ascending()).stream()
                .filter(w -> q.isEmpty()
                        || w.getFullName().toLowerCase().contains(q)
                        || w.getWorkerCode().toLowerCase().contains(q))
                .filter(w -> status == null || w.getStatus() == status)
                .toList();

        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        int from = Math.min(safePage * safeSize, filtered.size());
        int to = Math.min(from + safeSize, filtered.size());

        List<WorkerSummaryDto> dtos = filtered.subList(from, to).stream()
                .map(w -> WorkerSummaryDto.from(
                        w,
                        biometricRepository.findByWorkerId(w.getId()).isPresent(),
                        sessionRepository.findByWorkerIdAndRevokedAtUtcIsNull(w.getId()).size()
                ))
                .toList();

        return new PageImpl<>(dtos, PageRequest.of(safePage, safeSize), filtered.size());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public CreateWorkerResponse createAndInviteWorker(
            @Valid @RequestBody CreateWorkerRequest request,
            @AuthenticationPrincipal AdminPrincipal admin
    ) {
        String normalizedCode = request.workerCode().trim().toUpperCase();
        if (workerRepository.findByWorkerCode(normalizedCode).isPresent()) {
            throw new ConflictException("WORKER_CODE_EXISTS", "A worker with code " + normalizedCode + " already exists.");
        }

        Instant now = Instant.now();
        Worker worker = new Worker(
                UUID.randomUUID(),
                normalizedCode,
                request.fullName().trim(),
                request.teamName() != null ? request.teamName().trim() : null,
                WorkerStatus.INVITED,
                request.employmentStartDate(),
                null,
                now
        );
        workerRepository.save(worker);

        String plaintextCode = TokenHashUtil.generateInvitationCode("JOIN-" + normalizedCode);
        int validDays = request.invitationValidDays() != null && request.invitationValidDays() > 0
                ? Math.min(request.invitationValidDays(), 90)
                : 14;
        Instant expiresAt = now.plus(validDays, ChronoUnit.DAYS);

        WorkerInvitation invitation = new WorkerInvitation(
                UUID.randomUUID(),
                worker.getId(),
                TokenHashUtil.sha256Hex(plaintextCode),
                TokenHashUtil.maskInvitationCode(plaintextCode),
                expiresAt,
                admin != null ? admin.adminUserId() : null,
                now
        );
        invitationRepository.save(invitation);

        auditService.record(
                "WORKER_INVITATION_CREATED",
                AuditActorType.ADMIN,
                admin != null ? admin.adminUserId() : null,
                worker.getId(),
                Map.of("workerCode", normalizedCode, "codeHint", invitation.getCodeHint())
        );

        return new CreateWorkerResponse(
                WorkerSummaryDto.from(worker, false, 0),
                plaintextCode,
                expiresAt
        );
    }

    @PatchMapping("/{id}")
    @Transactional
    public WorkerSummaryDto updateWorker(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateWorkerRequest request,
            @AuthenticationPrincipal AdminPrincipal admin
    ) {
        Worker worker = workerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("WORKER_NOT_FOUND", "Worker not found."));
        if (request.fullName() != null && !request.fullName().isBlank()) {
            worker.setFullName(request.fullName().trim());
        }
        if (request.teamName() != null) {
            worker.setTeamName(request.teamName().isBlank() ? null : request.teamName().trim());
        }
        worker.setEmploymentStartDate(request.employmentStartDate());
        worker.setEmploymentEndDate(request.employmentEndDate());
        worker.setUpdatedAt(Instant.now());
        workerRepository.save(worker);

        auditService.record(
                "WORKER_UPDATED",
                AuditActorType.ADMIN,
                admin != null ? admin.adminUserId() : null,
                worker.getId(),
                Map.of("workerCode", worker.getWorkerCode())
        );

        return WorkerSummaryDto.from(
                worker,
                biometricRepository.findByWorkerId(worker.getId()).isPresent(),
                sessionRepository.findByWorkerIdAndRevokedAtUtcIsNull(worker.getId()).size()
        );
    }

    @PostMapping("/{id}/disable")
    @Transactional
    public WorkerSummaryDto disableWorker(
            @PathVariable UUID id,
            @AuthenticationPrincipal AdminPrincipal admin
    ) {
        Worker worker = workerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("WORKER_NOT_FOUND", "Worker not found."));
        Instant now = Instant.now();
        worker.setStatus(WorkerStatus.DISABLED);
        worker.setUpdatedAt(now);
        workerRepository.save(worker);

        int revoked = revokeAllSessions(worker.getId(), now);

        auditService.record(
                "WORKER_DISABLED",
                AuditActorType.ADMIN,
                admin != null ? admin.adminUserId() : null,
                worker.getId(),
                Map.of("workerCode", worker.getWorkerCode(), "revokedSessions", revoked)
        );

        return WorkerSummaryDto.from(worker, biometricRepository.findByWorkerId(worker.getId()).isPresent(), 0);
    }

    @PostMapping("/{id}/sessions/revoke")
    @Transactional
    public Map<String, Object> revokeSessions(
            @PathVariable UUID id,
            @AuthenticationPrincipal AdminPrincipal admin
    ) {
        Worker worker = workerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("WORKER_NOT_FOUND", "Worker not found."));
        int revoked = revokeAllSessions(worker.getId(), Instant.now());

        auditService.record(
                "WORKER_SESSIONS_REVOKED_BY_ADMIN",
                AuditActorType.ADMIN,
                admin != null ? admin.adminUserId() : null,
                worker.getId(),
                Map.of("workerCode", worker.getWorkerCode(), "revokedSessions", revoked)
        );
        return Map.of("workerId", worker.getId(), "revokedSessions", revoked);
    }

    @PostMapping("/{id}/biometric-reset")
    @Transactional
    public CreateWorkerResponse resetBiometrics(
            @PathVariable UUID id,
            @RequestBody(required = false) BiometricResetRequest request,
            @AuthenticationPrincipal AdminPrincipal admin
    ) {
        Worker worker = workerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("WORKER_NOT_FOUND", "Worker not found."));
        Instant now = Instant.now();

        biometricRepository.deleteByWorkerId(worker.getId());
        biometricRepository.flush();
        int revoked = revokeAllSessions(worker.getId(), now);

        worker.setStatus(WorkerStatus.INVITED);
        worker.setUpdatedAt(now);
        workerRepository.save(worker);

        String plaintextCode = TokenHashUtil.generateInvitationCode("JOIN-" + worker.getWorkerCode());
        Instant expiresAt = now.plus(14, ChronoUnit.DAYS);
        WorkerInvitation invitation = new WorkerInvitation(
                UUID.randomUUID(),
                worker.getId(),
                TokenHashUtil.sha256Hex(plaintextCode),
                TokenHashUtil.maskInvitationCode(plaintextCode),
                expiresAt,
                admin != null ? admin.adminUserId() : null,
                now
        );
        invitationRepository.save(invitation);

        auditService.record(
                "WORKER_BIOMETRIC_RESET",
                AuditActorType.ADMIN,
                admin != null ? admin.adminUserId() : null,
                worker.getId(),
                Map.of(
                        "workerCode", worker.getWorkerCode(),
                        "revokedSessions", revoked,
                        "reason", request != null && request.reason() != null ? request.reason() : "Admin reset"
                )
        );

        return new CreateWorkerResponse(
                WorkerSummaryDto.from(worker, false, 0),
                plaintextCode,
                expiresAt
        );
    }

    private int revokeAllSessions(UUID workerId, Instant now) {
        List<WorkerSession> active = sessionRepository.findByWorkerIdAndRevokedAtUtcIsNull(workerId);
        for (WorkerSession s : active) {
            s.setRevokedAtUtc(now);
        }
        sessionRepository.saveAll(active);
        return active.size();
    }

    public record CreateWorkerRequest(
            @NotBlank(message = "Worker code is required") @Size(max = 40) String workerCode,
            @NotBlank(message = "Full name is required") @Size(max = 160) String fullName,
            @Size(max = 100) String teamName,
            LocalDate employmentStartDate,
            Integer invitationValidDays
    ) {
    }

    public record UpdateWorkerRequest(
            @Size(max = 160) String fullName,
            @Size(max = 100) String teamName,
            LocalDate employmentStartDate,
            LocalDate employmentEndDate
    ) {
    }

    public record BiometricResetRequest(@Size(max = 300) String reason) {
    }

    public record WorkerSummaryDto(
            UUID id,
            String workerCode,
            String fullName,
            String teamName,
            WorkerStatus status,
            LocalDate employmentStartDate,
            LocalDate employmentEndDate,
            boolean biometricsEnrolled,
            int activeSessions
    ) {
        public static WorkerSummaryDto from(Worker worker, boolean biometricsEnrolled, int activeSessions) {
            return new WorkerSummaryDto(
                    worker.getId(),
                    worker.getWorkerCode(),
                    worker.getFullName(),
                    worker.getTeamName(),
                    worker.getStatus(),
                    worker.getEmploymentStartDate(),
                    worker.getEmploymentEndDate(),
                    biometricsEnrolled,
                    activeSessions
            );
        }
    }

    public record CreateWorkerResponse(
            WorkerSummaryDto worker,
            String invitationCode,
            Instant expiresAtUtc
    ) {
    }
}
