package com.example.attendance.workers;

import com.example.attendance.audit.AuditActorType;
import com.example.attendance.audit.AuditService;
import com.example.attendance.auth.CookieHelper;
import com.example.attendance.auth.RateLimiterService;
import com.example.attendance.auth.WorkerPrincipal;
import com.example.attendance.biometrics.WorkerBiometricRepository;
import com.example.attendance.common.ResourceNotFoundException;
import com.example.attendance.common.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class WorkerOnboardingController {

    private final WorkerOnboardingService onboardingService;
    private final WorkerRepository workerRepository;
    private final WorkerSessionRepository sessionRepository;
    private final WorkerBiometricRepository biometricRepository;
    private final CookieHelper cookieHelper;
    private final RateLimiterService rateLimiterService;
    private final AuditService auditService;

    public WorkerOnboardingController(
            WorkerOnboardingService onboardingService,
            WorkerRepository workerRepository,
            WorkerSessionRepository sessionRepository,
            WorkerBiometricRepository biometricRepository,
            CookieHelper cookieHelper,
            RateLimiterService rateLimiterService,
            AuditService auditService
    ) {
        this.onboardingService = onboardingService;
        this.workerRepository = workerRepository;
        this.sessionRepository = sessionRepository;
        this.biometricRepository = biometricRepository;
        this.cookieHelper = cookieHelper;
        this.rateLimiterService = rateLimiterService;
        this.auditService = auditService;
    }

    @PostMapping("/worker-invitations/validate")
    public WorkerOnboardingService.InvitationValidationResult validateInvitation(
            @Valid @RequestBody ValidateInvitationRequest request,
            HttpServletRequest httpRequest
    ) {
        rateLimiterService.checkLimit("invite-validate:" + httpRequest.getRemoteAddr(), 20, Duration.ofMinutes(5));
        return onboardingService.validateInvitation(request.invitationCode());
    }

    @PostMapping("/workers/register")
    public WorkerRegistrationResponse register(
            @Valid @RequestBody RegisterWorkerRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        rateLimiterService.checkLimit("worker-register:" + httpRequest.getRemoteAddr(), 15, Duration.ofMinutes(5));
        WorkerOnboardingService.RegistrationOutcome outcome = onboardingService.register(
                request.invitationCode(),
                request.fullName(),
                request.consentGiven(),
                request.consentVersion(),
                request.imageBase64(),
                request.deviceLabel(),
                httpRequest.getRemoteAddr()
        );
        cookieHelper.setSessionCookie(
                httpResponse,
                CookieHelper.WORKER_SESSION_COOKIE,
                outcome.rawSessionToken(),
                outcome.sessionTtl()
        );
        Worker worker = outcome.worker();
        return new WorkerRegistrationResponse(
                worker.getId(),
                worker.getWorkerCode(),
                worker.getFullName(),
                worker.getTeamName(),
                worker.getStatus(),
                outcome.mockBiometrics()
        );
    }

    @GetMapping("/workers/me")
    public WorkerMeResponse me(@AuthenticationPrincipal WorkerPrincipal principal) {
        if (principal == null) {
            throw new UnauthorizedException("UNAUTHORIZED", "Worker session is not active.");
        }
        Worker worker = workerRepository.findById(principal.workerId())
                .orElseThrow(() -> new ResourceNotFoundException("WORKER_NOT_FOUND", "Worker not found."));
        boolean enrolled = biometricRepository.findByWorkerId(worker.getId()).isPresent();
        return new WorkerMeResponse(
                worker.getId(),
                worker.getWorkerCode(),
                worker.getFullName(),
                worker.getTeamName(),
                worker.getStatus(),
                enrolled
        );
    }

    @PostMapping("/workers/logout")
    @Transactional
    public Map<String, Object> logout(
            @AuthenticationPrincipal WorkerPrincipal principal,
            HttpServletResponse httpResponse
    ) {
        if (principal != null) {
            sessionRepository.findById(principal.sessionId()).ifPresent(session -> {
                session.setRevokedAtUtc(Instant.now());
                sessionRepository.save(session);
            });
            auditService.record("WORKER_LOGOUT", AuditActorType.WORKER, principal.workerId(), principal.workerId(), Map.of());
        }
        cookieHelper.clearSessionCookie(httpResponse, CookieHelper.WORKER_SESSION_COOKIE);
        return Map.of("loggedOut", true);
    }

    @PostMapping("/workers/device/revoke")
    @Transactional
    public Map<String, Object> revokeDeviceSessions(
            @AuthenticationPrincipal WorkerPrincipal principal,
            HttpServletResponse httpResponse
    ) {
        if (principal == null) {
            throw new UnauthorizedException("UNAUTHORIZED", "Worker session is not active.");
        }
        Instant now = Instant.now();
        List<WorkerSession> active = sessionRepository.findByWorkerIdAndRevokedAtUtcIsNull(principal.workerId());
        for (WorkerSession session : active) {
            session.setRevokedAtUtc(now);
        }
        sessionRepository.saveAll(active);
        auditService.record(
                "WORKER_DEVICE_REVOKED",
                AuditActorType.WORKER,
                principal.workerId(),
                principal.workerId(),
                Map.of("revokedSessions", active.size())
        );
        cookieHelper.clearSessionCookie(httpResponse, CookieHelper.WORKER_SESSION_COOKIE);
        return Map.of("revokedCount", active.size());
    }

    public record ValidateInvitationRequest(
            @NotBlank(message = "Invitation code is required") String invitationCode
    ) {
    }

    public record RegisterWorkerRequest(
            @NotBlank(message = "Invitation code is required") String invitationCode,
            @NotBlank(message = "Full name is required") @Size(max = 160) String fullName,
            boolean consentGiven,
            String consentVersion,
            String imageBase64,
            String deviceLabel
    ) {
    }

    public record WorkerRegistrationResponse(
            UUID id,
            String workerCode,
            String fullName,
            String teamName,
            WorkerStatus status,
            boolean mockBiometrics
    ) {
    }

    public record WorkerMeResponse(
            UUID id,
            String workerCode,
            String fullName,
            String teamName,
            WorkerStatus status,
            boolean biometricsEnrolled
    ) {
    }
}
