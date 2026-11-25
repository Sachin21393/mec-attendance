package com.example.attendance;

import com.example.attendance.auth.CookieHelper;
import com.example.attendance.auth.RateLimiterService;
import com.example.attendance.biometrics.WorkerBiometricRepository;
import com.example.attendance.workers.ConsentRecordRepository;
import com.example.attendance.workers.WorkerRepository;
import com.example.attendance.workers.WorkerStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AuthAndRegistrationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WorkerRepository workerRepository;

    @Autowired
    private WorkerBiometricRepository biometricRepository;

    @Autowired
    private ConsentRecordRepository consentRecordRepository;

    @Autowired
    private RateLimiterService rateLimiterService;

    @BeforeEach
    void resetRateLimits() {
        rateLimiterService.resetAll();
    }

    private Cookie loginAsAdmin() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "admin",
                                "password", "TestAdminPassword123!"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.role").value("TECH_ADMIN"))
                .andReturn();

        Cookie cookie = result.getResponse().getCookie(CookieHelper.ADMIN_SESSION_COOKIE);
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        return cookie;
    }

    @Test
    void adminAuthLifecycleRoleSeparationAndCsrf() throws Exception {
        // Unauthenticated admin request rejected
        mockMvc.perform(get("/api/v1/admin/workers"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        Cookie adminCookie = loginAsAdmin();

        // CSRF missing on state-changing admin endpoint rejected
        mockMvc.perform(post("/api/v1/admin/workers")
                        .cookie(adminCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workerCode\":\"W999\",\"fullName\":\"No Csrf\"}"))
                .andExpect(status().isForbidden());

        // Admin cannot call worker-only endpoint using admin cookie
        mockMvc.perform(get("/api/v1/workers/me").cookie(adminCookie))
                .andExpect(status().isUnauthorized());

        // Admin me & logout
        mockMvc.perform(get("/api/v1/admin/auth/me").cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("admin"));

        mockMvc.perform(post("/api/v1/admin/auth/logout").with(csrf()).cookie(adminCookie))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/admin/auth/me").cookie(adminCookie))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminLoginRateLimitBlocksRepeatedFailures() throws Exception {
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/v1/admin/auth/login")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/api/v1/admin/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void workerInviteConsentDeclineHappyPathAndOneTimeUse() throws Exception {
        Cookie adminCookie = loginAsAdmin();

        // Admin creates a new worker + invitation
        MvcResult createRes = mockMvc.perform(post("/api/v1/admin/workers")
                        .with(csrf())
                        .cookie(adminCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "workerCode", "W101",
                                "fullName", "Neha Singh",
                                "teamName", "Quality"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.worker.workerCode").value("W101"))
                .andExpect(jsonPath("$.worker.status").value("INVITED"))
                .andReturn();

        JsonNode created = objectMapper.readTree(createRes.getResponse().getContentAsString());
        String inviteCode = created.get("invitationCode").asText();
        assertThat(inviteCode).isNotBlank();

        // Validate invitation
        mockMvc.perform(post("/api/v1/worker-invitations/validate")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("invitationCode", inviteCode))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workerCode").value("W101"))
                .andExpect(jsonPath("$.consentVersion").isNotEmpty());

        // Declining consent does NOT enroll biometrics
        mockMvc.perform(post("/api/v1/workers/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "invitationCode", inviteCode,
                                "fullName", "Neha Singh",
                                "consentGiven", false,
                                "consentVersion", "2026-11-v1"
                        ))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CONSENT_REQUIRED_FOR_BIOMETRICS"));

        var worker = workerRepository.findByWorkerCode("W101").orElseThrow();
        assertThat(worker.getStatus()).isEqualTo(WorkerStatus.INVITED);
        assertThat(biometricRepository.findByWorkerId(worker.getId())).isEmpty();
        assertThat(consentRecordRepository.findByWorkerIdOrderByRecordedAtUtcDesc(worker.getId()))
                .hasSize(1)
                .allMatch(c -> !c.isConsentGiven());

        // Registration with consent succeeds
        String syntheticFace = Base64.getEncoder().encodeToString("SYNTHETIC_FACE_PAYLOAD_W101".getBytes(StandardCharsets.UTF_8));
        MvcResult regRes = mockMvc.perform(post("/api/v1/workers/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "invitationCode", inviteCode,
                                "fullName", "Neha S. Verma",
                                "consentGiven", true,
                                "consentVersion", "2026-11-v1",
                                "imageBase64", syntheticFace,
                                "deviceLabel", "Pixel Test"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workerCode").value("W101"))
                .andExpect(jsonPath("$.fullName").value("Neha S. Verma"))
                .andExpect(jsonPath("$.status").value("REGISTERED"))
                .andExpect(jsonPath("$.mockBiometrics").value(true))
                .andReturn();

        Cookie workerCookie = regRes.getResponse().getCookie(CookieHelper.WORKER_SESSION_COOKIE);
        assertThat(workerCookie).isNotNull();
        assertThat(workerCookie.isHttpOnly()).isTrue();
        assertThat(biometricRepository.findByWorkerId(worker.getId())).isPresent();

        // Worker /me works and never exposes embeddings; worker cannot access admin routes
        mockMvc.perform(get("/api/v1/workers/me").cookie(workerCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workerCode").value("W101"))
                .andExpect(jsonPath("$.biometricsEnrolled").value(true))
                .andExpect(jsonPath("$.encryptedEmbedding").doesNotExist());

        mockMvc.perform(get("/api/v1/admin/workers").cookie(workerCookie))
                .andExpect(status().isUnauthorized());

        // Invitation cannot be reused
        mockMvc.perform(post("/api/v1/workers/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "invitationCode", inviteCode,
                                "fullName", "Imposter",
                                "consentGiven", true,
                                "imageBase64", syntheticFace
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVITATION_ALREADY_USED"));

        // Logout invalidates worker session
        mockMvc.perform(post("/api/v1/workers/logout").with(csrf()).cookie(workerCookie))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/workers/me").cookie(workerCookie))
                .andExpect(status().isUnauthorized());
    }
}
