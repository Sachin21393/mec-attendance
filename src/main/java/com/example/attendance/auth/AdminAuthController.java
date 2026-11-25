package com.example.attendance.auth;

import com.example.attendance.audit.AuditActorType;
import com.example.attendance.audit.AuditService;
import com.example.attendance.common.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/auth")
public class AdminAuthController {

    private final AdminUserRepository adminUserRepository;
    private final AdminSessionRepository adminSessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final CookieHelper cookieHelper;
    private final RateLimiterService rateLimiterService;
    private final AuditService auditService;

    public AdminAuthController(
            AdminUserRepository adminUserRepository,
            AdminSessionRepository adminSessionRepository,
            PasswordEncoder passwordEncoder,
            CookieHelper cookieHelper,
            RateLimiterService rateLimiterService,
            AuditService auditService
    ) {
        this.adminUserRepository = adminUserRepository;
        this.adminSessionRepository = adminSessionRepository;
        this.passwordEncoder = passwordEncoder;
        this.cookieHelper = cookieHelper;
        this.rateLimiterService = rateLimiterService;
        this.auditService = auditService;
    }

    @PostMapping("/login")
    public AdminProfileResponse login(
            @Valid @RequestBody AdminLoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        String key = "admin-login:" + httpRequest.getRemoteAddr() + ":" + request.username().trim().toLowerCase();
        rateLimiterService.checkLimit(key, 10, Duration.ofMinutes(5));

        AdminUser admin = adminUserRepository.findByUsername(request.username().trim())
                .filter(AdminUser::isEnabled)
                .orElse(null);

        if (admin == null || !passwordEncoder.matches(request.password(), admin.getPasswordHash())) {
            auditService.recordIndependent(
                    "ADMIN_LOGIN_FAILED",
                    AuditActorType.ADMIN,
                    admin != null ? admin.getId() : null,
                    null,
                    Map.of("username", request.username().trim())
            );
            throw new UnauthorizedException("INVALID_CREDENTIALS", "Invalid username or password.");
        }

        String rawToken = TokenHashUtil.generateOpaqueToken();
        String tokenHash = TokenHashUtil.sha256Hex(rawToken);
        Instant now = Instant.now();
        Duration ttl = Duration.ofHours(12);
        AdminSession session = new AdminSession(UUID.randomUUID(), admin.getId(), tokenHash, now.plus(ttl), now);
        adminSessionRepository.save(session);

        cookieHelper.setSessionCookie(httpResponse, CookieHelper.ADMIN_SESSION_COOKIE, rawToken, ttl);
        auditService.record(
                "ADMIN_LOGIN_SUCCESS",
                AuditActorType.ADMIN,
                admin.getId(),
                null,
                Map.of("username", admin.getUsername(), "role", admin.getRole().name())
        );

        return new AdminProfileResponse(admin.getId(), admin.getUsername(), admin.getDisplayName(), admin.getRole());
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(
            @AuthenticationPrincipal AdminPrincipal principal,
            HttpServletResponse httpResponse
    ) {
        if (principal != null) {
            adminSessionRepository.findById(principal.sessionId()).ifPresent(session -> {
                session.setRevokedAtUtc(Instant.now());
                adminSessionRepository.save(session);
            });
            auditService.record("ADMIN_LOGOUT", AuditActorType.ADMIN, principal.adminUserId(), null, Map.of());
        }
        cookieHelper.clearSessionCookie(httpResponse, CookieHelper.ADMIN_SESSION_COOKIE);
        return Map.of("loggedOut", true);
    }

    @GetMapping("/me")
    public AdminProfileResponse me(@AuthenticationPrincipal AdminPrincipal principal) {
        if (principal == null) {
            throw new UnauthorizedException("UNAUTHORIZED", "Authentication is required.");
        }
        return new AdminProfileResponse(
                principal.adminUserId(),
                principal.username(),
                principal.displayName(),
                principal.role()
        );
    }

    public record AdminLoginRequest(
            @NotBlank(message = "Username is required") String username,
            @NotBlank(message = "Password is required") String password
    ) {
    }

    public record AdminProfileResponse(
            UUID id,
            String username,
            String displayName,
            AdminRole role
    ) {
    }
}
