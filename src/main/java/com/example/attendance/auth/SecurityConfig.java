package com.example.attendance.auth;

import com.example.attendance.common.ApiError;
import com.example.attendance.common.AttendanceProperties;
import com.example.attendance.common.CorrelationIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final SessionAuthenticationFilter sessionAuthenticationFilter;
    private final AttendanceProperties properties;
    private final ObjectMapper objectMapper;

    public SecurityConfig(
            SessionAuthenticationFilter sessionAuthenticationFilter,
            AttendanceProperties properties,
            ObjectMapper objectMapper
    ) {
        this.sessionAuthenticationFilter = sessionAuthenticationFilter;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        CookieCsrfTokenRepository csrfRepo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepo.setCookiePath("/");
        csrfRepo.setCookieCustomizer(c -> c
                .sameSite("Lax")
                .secure(properties.getSecurity().isCookieSecure()));

        CsrfTokenRequestAttributeHandler requestHandler = new CsrfTokenRequestAttributeHandler();
        requestHandler.setCsrfRequestAttributeName(null);

        http
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                        .referrerPolicy(ref -> ref.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .permissionsPolicy(perm -> perm.policy("camera=(self), microphone=(), geolocation=(self)"))
                )
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepo)
                        .csrfTokenRequestHandler(requestHandler)
                )
                .addFilterBefore(sessionAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, authEx) -> writeError(
                                res, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required."))
                        .accessDeniedHandler((req, res, deniedEx) -> writeError(
                                res, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "You do not have permission to perform this action."))
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/", "/index.html",
                                "/worker", "/worker/**", "/employee", "/employee/**", "/admin", "/admin/**", "/shared/**", "/assets/**",
                                "/manifest.webmanifest", "/service-worker.js", "/favicon.ico"
                        ).permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/auth/csrf",
                                "/api/v1/biometrics/config",
                                "/api/v1/attendance/kiosk/status"
                        ).permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/admin/auth/login",
                                "/api/v1/worker-invitations/validate",
                                "/api/v1/workers/register",
                                "/api/v1/workers/logout",
                                "/api/v1/attendance/kiosk/location-check",
                                "/api/v1/attendance/kiosk/verify",
                                "/api/v1/attendance/kiosk/check-in",
                                "/api/v1/attendance/kiosk/mark"
                        ).permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/workers/**", "/api/v1/biometrics/**", "/api/v1/attendance/**").hasRole("WORKER")
                        .anyRequest().authenticated()
                );
        return http.build();
    }

    private void writeError(HttpServletResponse response, int status, String code, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiError error = new ApiError(code, message, CorrelationIdFilter.currentOrGenerate());
        objectMapper.writeValue(response.getWriter(), error);
    }
}
