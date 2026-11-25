package com.example.attendance.auth;

import com.example.attendance.workers.Worker;
import com.example.attendance.workers.WorkerRepository;
import com.example.attendance.workers.WorkerSession;
import com.example.attendance.workers.WorkerSessionRepository;
import com.example.attendance.workers.WorkerStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class SessionAuthenticationFilter extends OncePerRequestFilter {

    private final CookieHelper cookieHelper;
    private final AdminSessionRepository adminSessionRepository;
    private final AdminUserRepository adminUserRepository;
    private final WorkerSessionRepository workerSessionRepository;
    private final WorkerRepository workerRepository;

    public SessionAuthenticationFilter(
            CookieHelper cookieHelper,
            AdminSessionRepository adminSessionRepository,
            AdminUserRepository adminUserRepository,
            WorkerSessionRepository workerSessionRepository,
            WorkerRepository workerRepository
    ) {
        this.cookieHelper = cookieHelper;
        this.adminSessionRepository = adminSessionRepository;
        this.adminUserRepository = adminUserRepository;
        this.workerSessionRepository = workerSessionRepository;
        this.workerRepository = workerRepository;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            if (path.startsWith("/api/v1/admin")) {
                authenticateAdmin(request);
            } else if (path.startsWith("/api/v1/workers")
                    || path.startsWith("/api/v1/attendance")
                    || path.startsWith("/api/v1/biometrics")) {
                authenticateWorker(request);
            }
        }
        filterChain.doFilter(request, response);
    }

    private void authenticateAdmin(HttpServletRequest request) {
        cookieHelper.readCookie(request, CookieHelper.ADMIN_SESSION_COOKIE).ifPresent(rawToken -> {
            String hash = TokenHashUtil.sha256Hex(rawToken);
            Instant now = Instant.now();
            adminSessionRepository.findByTokenHash(hash)
                    .filter(s -> s.isActive(now))
                    .ifPresent(session -> adminUserRepository.findById(session.getAdminUserId())
                            .filter(AdminUser::isEnabled)
                            .ifPresent(admin -> {
                                session.setLastUsedAtUtc(now);
                                adminSessionRepository.save(session);
                                List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                                authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
                                if (admin.getRole() == AdminRole.TECH_ADMIN) {
                                    authorities.add(new SimpleGrantedAuthority("ROLE_TECH_ADMIN"));
                                }
                                AdminPrincipal principal = new AdminPrincipal(
                                        admin.getId(), admin.getUsername(), admin.getDisplayName(), admin.getRole(), session.getId());
                                UsernamePasswordAuthenticationToken auth =
                                        new UsernamePasswordAuthenticationToken(principal, null, authorities);
                                SecurityContextHolder.getContext().setAuthentication(auth);
                            }));
        });
    }

    private void authenticateWorker(HttpServletRequest request) {
        cookieHelper.readCookie(request, CookieHelper.WORKER_SESSION_COOKIE).ifPresent(rawToken -> {
            String hash = TokenHashUtil.sha256Hex(rawToken);
            Instant now = Instant.now();
            workerSessionRepository.findByTokenHash(hash)
                    .filter(s -> s.isActive(now))
                    .ifPresent(session -> workerRepository.findById(session.getWorkerId())
                            .filter(w -> w.getStatus() == WorkerStatus.REGISTERED)
                            .ifPresent(worker -> {
                                session.setLastUsedAtUtc(now);
                                workerSessionRepository.save(session);
                                WorkerPrincipal principal = new WorkerPrincipal(
                                        worker.getId(), worker.getWorkerCode(), worker.getFullName(), session.getId());
                                UsernamePasswordAuthenticationToken auth =
                                        new UsernamePasswordAuthenticationToken(
                                                principal,
                                                null,
                                                List.of(new SimpleGrantedAuthority("ROLE_WORKER"))
                                        );
                                SecurityContextHolder.getContext().setAuthentication(auth);
                            }));
        });
    }
}
