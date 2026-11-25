package com.example.attendance.workers;

import com.example.attendance.audit.AuditActorType;
import com.example.attendance.audit.AuditService;
import com.example.attendance.auth.AdminPrincipal;
import com.example.attendance.auth.TokenHashUtil;
import com.example.attendance.common.ConflictException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/workers")
public class AdminWorkerController {

    private final WorkerRepository workerRepository;
    private final WorkerInvitationRepository invitationRepository;
    private final AuditService auditService;

    public AdminWorkerController(
            WorkerRepository workerRepository,
            WorkerInvitationRepository invitationRepository,
            AuditService auditService
    ) {
        this.workerRepository = workerRepository;
        this.invitationRepository = invitationRepository;
        this.auditService = auditService;
    }

    @GetMapping
    public Page<WorkerSummaryDto> listWorkers(
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by("workerCode").ascending());
        Page<Worker> workers = (query == null || query.isBlank())
                ? workerRepository.findAll(pageable)
                : workerRepository.findByFullNameContainingIgnoreCaseOrWorkerCodeContainingIgnoreCase(
                        query.trim(), query.trim(), pageable);
        return workers.map(WorkerSummaryDto::from);
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
                WorkerSummaryDto.from(worker),
                plaintextCode,
                expiresAt
        );
    }

    public record CreateWorkerRequest(
            @NotBlank(message = "Worker code is required") @Size(max = 40) String workerCode,
            @NotBlank(message = "Full name is required") @Size(max = 160) String fullName,
            @Size(max = 100) String teamName,
            LocalDate employmentStartDate,
            Integer invitationValidDays
    ) {
    }

    public record WorkerSummaryDto(
            UUID id,
            String workerCode,
            String fullName,
            String teamName,
            WorkerStatus status,
            LocalDate employmentStartDate,
            LocalDate employmentEndDate
    ) {
        public static WorkerSummaryDto from(Worker worker) {
            return new WorkerSummaryDto(
                    worker.getId(),
                    worker.getWorkerCode(),
                    worker.getFullName(),
                    worker.getTeamName(),
                    worker.getStatus(),
                    worker.getEmploymentStartDate(),
                    worker.getEmploymentEndDate()
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
