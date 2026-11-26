package com.example.attendance;

import com.example.attendance.attendance.AttendanceCorrectionRepository;
import com.example.attendance.auth.CookieHelper;
import com.example.attendance.auth.RateLimiterService;
import com.example.attendance.biometrics.WorkerBiometricRepository;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class WorkerManagementAndAuditTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WorkerRepository workerRepository;

    @Autowired
    private WorkerBiometricRepository biometricRepository;

    @Autowired
    private AttendanceCorrectionRepository correctionRepository;

    @Autowired
    private RateLimiterService rateLimiterService;

    @BeforeEach
    void setup() {
        rateLimiterService.resetAll();
    }

    private Cookie loginAsAdmin() throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/admin/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "admin",
                                "password", "TestAdminPassword123!"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return res.getResponse().getCookie(CookieHelper.ADMIN_SESSION_COOKIE);
    }

    @Test
    void disableRevokeAndBiometricResetLifecycleEnforcesSecurityAndAllowsReRegistration() throws Exception {
        Cookie adminCookie = loginAsAdmin();

        // Register W009
        String face1 = Base64.getEncoder().encodeToString("MOCK_FACE_W009".getBytes(StandardCharsets.UTF_8));
        MvcResult regRes = mockMvc.perform(post("/api/v1/workers/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "invitationCode", "JOIN-W009-DEMO",
                                "fullName", "Deepak Kulkarni",
                                "consentGiven", true,
                                "imageBase64", face1
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        Cookie workerCookie = regRes.getResponse().getCookie(CookieHelper.WORKER_SESSION_COOKIE);
        UUID w009Id = UUID.fromString("10000000-0000-0000-0000-000000000009");

        // Admin worker list never exposes embeddings
        mockMvc.perform(get("/api/v1/admin/workers?query=W009").cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].biometricsEnrolled").value(true))
                .andExpect(jsonPath("$.content[0].activeSessions").value(1))
                .andExpect(jsonPath("$.content[0].encryptedEmbedding").doesNotExist());

        // Revoke sessions invalidates worker cookie
        mockMvc.perform(post("/api/v1/admin/workers/" + w009Id + "/sessions/revoke")
                        .with(csrf())
                        .cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revokedSessions").value(1));

        mockMvc.perform(get("/api/v1/workers/me").cookie(workerCookie))
                .andExpect(status().isUnauthorized());

        // Biometric reset clears template, sets INVITED, and issues fresh one-time code
        MvcResult resetRes = mockMvc.perform(post("/api/v1/admin/workers/" + w009Id + "/biometric-reset")
                        .with(csrf())
                        .cookie(adminCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Worker changed phone and needs re-enrollment\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.worker.status").value("INVITED"))
                .andReturn();
        String newCode = objectMapper.readTree(resetRes.getResponse().getContentAsString()).get("invitationCode").asText();
        assertThat(biometricRepository.findByWorkerId(w009Id)).isEmpty();

        // Re-register with the new code
        String face2 = Base64.getEncoder().encodeToString("MOCK_FACE_W009_NEW".getBytes(StandardCharsets.UTF_8));
        MvcResult reRegRes = mockMvc.perform(post("/api/v1/workers/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "invitationCode", newCode,
                                "fullName", "Deepak Kulkarni",
                                "consentGiven", true,
                                "imageBase64", face2
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        Cookie freshWorkerCookie = reRegRes.getResponse().getCookie(CookieHelper.WORKER_SESSION_COOKIE);

        // Disable worker immediately revokes active session and blocks worker routes
        mockMvc.perform(post("/api/v1/admin/workers/" + w009Id + "/disable")
                        .with(csrf())
                        .cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));

        assertThat(workerRepository.findById(w009Id).orElseThrow().getStatus()).isEqualTo(WorkerStatus.DISABLED);
        mockMvc.perform(get("/api/v1/workers/me").cookie(freshWorkerCookie))
                .andExpect(status().isUnauthorized());

        // Update profile
        mockMvc.perform(patch("/api/v1/admin/workers/" + w009Id)
                        .with(csrf())
                        .cookie(adminCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "fullName", "Deepak K.",
                                "teamName", "Quality"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Deepak K."))
                .andExpect(jsonPath("$.teamName").value("Quality"));
    }

    @Test
    void recordsManualExceptionsAndCorrectionsWithFullAuditTrail() throws Exception {
        Cookie adminCookie = loginAsAdmin();
        UUID w005Id = UUID.fromString("10000000-0000-0000-0000-000000000005");

        // Manual exception on 2026-11-10 (camera unavailable)
        MvcResult manualRes = mockMvc.perform(post("/api/v1/admin/attendance/manual")
                        .with(csrf())
                        .cookie(adminCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "workerId", w005Id.toString(),
                                "attendanceDate", "2026-11-10",
                                "status", "ON_TIME",
                                "checkInLocalTime", "08:55",
                                "reason", "Camera hardware repair in progress"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attendanceMethod").value("ADMIN_MANUAL"))
                .andExpect(jsonPath("$.attendanceStatus").value("ON_TIME"))
                .andReturn();
        String recordId = objectMapper.readTree(manualRes.getResponse().getContentAsString()).get("id").asText();

        // Correct that record from ON_TIME to LATE with reason
        mockMvc.perform(post("/api/v1/admin/attendance/" + recordId + "/correct")
                        .with(csrf())
                        .cookie(adminCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "newStatus", "LATE",
                                "reason", "Supervisor log confirmed 09:30 arrival"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previousStatus").value("ON_TIME"))
                .andExpect(jsonPath("$.newStatus").value("LATE"));

        assertThat(correctionRepository.findByAttendanceRecordIdOrderByCreatedAtDesc(UUID.fromString(recordId)))
                .hasSize(2);

        // Audit log query filtered by eventType & subjectWorkerId preserves history
        MvcResult auditRes = mockMvc.perform(get("/api/v1/admin/audit-events")
                        .param("eventType", "ATTENDANCE_CORRECTED")
                        .param("subjectWorkerId", w005Id.toString())
                        .cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andReturn();

        JsonNode auditNode = objectMapper.readTree(auditRes.getResponse().getContentAsString());
        assertThat(auditNode.get("content").get(0).get("metadataJson").asText())
                .contains("Supervisor log confirmed 09:30 arrival");
    }

    @Test
    void singleAndBulkWorkerDeleteRequireAdminPasswordAndReasonAndCleanDependencies() throws Exception {
        Cookie adminCookie = loginAsAdmin();
        UUID w001Id = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID w003Id = UUID.fromString("10000000-0000-0000-0000-000000000003");
        UUID w004Id = UUID.fromString("10000000-0000-0000-0000-000000000004");

        // Single delete rejects wrong admin password
        mockMvc.perform(post("/api/v1/admin/workers/" + w001Id + "/delete")
                        .with(csrf())
                        .cookie(adminCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "adminPassword", "WrongPassword!",
                                "reason", "Left company"
                        ))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_ADMIN_PASSWORD"));
        assertThat(workerRepository.findById(w001Id)).isPresent();

        // Single delete succeeds with correct admin password even when worker has proxy + self records
        mockMvc.perform(post("/api/v1/admin/workers/" + w001Id + "/delete")
                        .with(csrf())
                        .cookie(adminCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "adminPassword", "TestAdminPassword123!",
                                "reason", "Resigned"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedCount").value(1))
                .andExpect(jsonPath("$.deletedWorkerCodes[0]").value("W001"));
        assertThat(workerRepository.findById(w001Id)).isEmpty();
        assertThat(biometricRepository.findByWorkerId(w001Id)).isEmpty();

        // Bulk delete requires reason
        mockMvc.perform(post("/api/v1/admin/workers/bulk-delete")
                        .with(csrf())
                        .cookie(adminCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "workerIds", java.util.List.of(w003Id.toString(), w004Id.toString()),
                                "reason", "",
                                "adminPassword", "TestAdminPassword123!"
                        ))))
                .andExpect(status().isBadRequest());

        // Bulk delete succeeds with valid reason and password
        mockMvc.perform(post("/api/v1/admin/workers/bulk-delete")
                        .with(csrf())
                        .cookie(adminCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "workerIds", java.util.List.of(w003Id.toString(), w004Id.toString()),
                                "reason", "Contract ended",
                                "adminPassword", "TestAdminPassword123!"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedCount").value(2));
        assertThat(workerRepository.findById(w003Id)).isEmpty();
        assertThat(workerRepository.findById(w004Id)).isEmpty();

        // Audit trail preserved for bulk deletion with reason
        MvcResult auditRes = mockMvc.perform(get("/api/v1/admin/audit-events")
                        .param("eventType", "WORKERS_BULK_DELETED")
                        .cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andReturn();
        assertThat(objectMapper.readTree(auditRes.getResponse().getContentAsString())
                .get("content").get(0).get("metadataJson").asText())
                .contains("Contract ended");
    }
}
