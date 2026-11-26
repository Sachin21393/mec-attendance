package com.example.attendance;

import com.example.attendance.attendance.AttendanceCalculator;
import com.example.attendance.attendance.AttendanceMethod;
import com.example.attendance.attendance.AttendanceRecordRepository;
import com.example.attendance.auth.CookieHelper;
import com.example.attendance.auth.RateLimiterService;
import com.example.attendance.settings.AttendanceSettings;
import com.example.attendance.settings.AttendanceSettingsRepository;
import com.example.attendance.settings.DayType;
import com.example.attendance.settings.WorkCalendarDay;
import com.example.attendance.settings.WorkCalendarRepository;
import com.example.attendance.workers.WorkerInvitationRepository;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

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
class AdminBulkEnrollAndKioskCheckInTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AttendanceCalculator calculator;

    @Autowired
    private AttendanceSettingsRepository settingsRepository;

    @Autowired
    private WorkCalendarRepository calendarRepository;

    @Autowired
    private WorkerRepository workerRepository;

    @Autowired
    private WorkerInvitationRepository invitationRepository;

    @Autowired
    private AttendanceRecordRepository attendanceRepository;

    @Autowired
    private RateLimiterService rateLimiterService;

    private LocalDate workplaceToday;

    @BeforeEach
    void setup() {
        rateLimiterService.resetAll();
        AttendanceSettings settings = settingsRepository.findTopByOrderByEffectiveFromUtcDesc().orElseThrow();
        workplaceToday = calculator.resolveAttendanceDate(Instant.now(), settings);
        if (calendarRepository.findByCalendarDate(workplaceToday).isEmpty()) {
            calendarRepository.saveAndFlush(new WorkCalendarDay(
                    UUID.randomUUID(), workplaceToday, DayType.WORKING,
                    LocalTime.of(9, 0), 15, LocalTime.of(18, 0),
                    settings.getTimezoneId(), "Test Working Day", null, Instant.now()));
        } else {
            WorkCalendarDay day = calendarRepository.findByCalendarDate(workplaceToday).orElseThrow();
            day.setDayType(DayType.WORKING);
            calendarRepository.saveAndFlush(day);
        }
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
    void adminBulkEnrollsNewAndExistingWorkersAndKioskMarksAttendanceWithoutRegistration() throws Exception {
        Cookie adminCookie = loginAsAdmin();

        String w901Face = Base64.getEncoder().encodeToString("MOCK_FACE_W901_ADMIN".getBytes(StandardCharsets.UTF_8));
        // Requires consent
        mockMvc.perform(post("/api/v1/admin/workers/enroll")
                        .cookie(adminCookie)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "workerCode", "W901",
                                "fullName", "Neha Sharma",
                                "teamName", "Packaging",
                                "consentGiven", false,
                                "imageBase64", w901Face
                        ))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CONSENT_REQUIRED_FOR_BIOMETRICS"));

        // Enroll brand new worker directly from admin dashboard
        MvcResult enrollNewRes = mockMvc.perform(post("/api/v1/admin/workers/enroll")
                        .cookie(adminCookie)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "workerCode", "w901",
                                "fullName", "Neha Sharma",
                                "teamName", "Packaging",
                                "consentGiven", true,
                                "imageBase64", w901Face
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.workerCode").value("W901"))
                .andExpect(jsonPath("$.fullName").value("Neha Sharma"))
                .andExpect(jsonPath("$.status").value("REGISTERED"))
                .andExpect(jsonPath("$.biometricsEnrolled").value(true))
                .andReturn();
        UUID w901Id = UUID.fromString(objectMapper.readTree(enrollNewRes.getResponse().getContentAsString()).get("id").asText());

        // Enroll existing invited worker W009 and consume open invitation
        UUID w009Id = UUID.fromString("10000000-0000-0000-0000-000000000009");
        String w009Face = Base64.getEncoder().encodeToString("MOCK_FACE_W009_ADMIN".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(post("/api/v1/admin/workers/enroll")
                        .cookie(adminCookie)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "workerId", w009Id.toString(),
                                "fullName", "Deepak Kulkarni",
                                "teamName", "Assembly",
                                "consentGiven", true,
                                "imageBase64", w009Face
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.workerCode").value("W009"))
                .andExpect(jsonPath("$.status").value("REGISTERED"))
                .andExpect(jsonPath("$.biometricsEnrolled").value(true));

        assertThat(workerRepository.findById(w009Id).orElseThrow().getStatus()).isEqualTo(WorkerStatus.REGISTERED);
        assertThat(invitationRepository.findByWorkerIdAndConsumedAtUtcIsNull(w009Id)).isEmpty();

        // Kiosk worker app status requires no worker session
        mockMvc.perform(get("/api/v1/attendance/kiosk/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attendanceDate").value(workplaceToday.toString()))
                .andExpect(jsonPath("$.dayType").value("WORKING"));

        // Direct 1:N face verification without worker session cookie
        MvcResult kioskVerifyRes = mockMvc.perform(post("/api/v1/attendance/kiosk/verify")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "imageBase64", w901Face
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchedWorkerCode").value("W901"))
                .andExpect(jsonPath("$.matchedWorkerName").value("Neha Sharma"))
                .andReturn();
        String kioskToken = objectMapper.readTree(kioskVerifyRes.getResponse().getContentAsString())
                .get("verificationToken").asText();

        String idemKey = UUID.randomUUID().toString();
        MvcResult kioskCheckInRes = mockMvc.perform(post("/api/v1/attendance/kiosk/check-in")
                        .with(csrf())
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "verificationToken", kioskToken,
                                "idempotencyKey", idemKey
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjectWorkerCode").value("W901"))
                .andExpect(jsonPath("$.attendanceMethod").value("SELF"))
                .andExpect(jsonPath("$.idempotentReplay").value(false))
                .andReturn();
        JsonNode checkInJson = objectMapper.readTree(kioskCheckInRes.getResponse().getContentAsString());
        assertThat(checkInJson.get("subjectWorkerId").asText()).isEqualTo(w901Id.toString());

        // Idempotent replay succeeds cleanly
        mockMvc.perform(post("/api/v1/attendance/kiosk/check-in")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "verificationToken", kioskToken,
                                "idempotencyKey", idemKey
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotentReplay").value(true));

        assertThat(attendanceRepository.findBySubjectWorkerIdAndAttendanceDate(w901Id, workplaceToday))
                .isPresent()
                .get()
                .satisfies(rec -> assertThat(rec.getAttendanceMethod()).isEqualTo(AttendanceMethod.SELF));

        // Second attendance attempt on same day with new token fails with ALREADY_MARKED
        MvcResult verifyAgainRes = mockMvc.perform(post("/api/v1/attendance/kiosk/verify")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("imageBase64", w901Face))))
                .andExpect(status().isOk())
                .andReturn();
        String token2 = objectMapper.readTree(verifyAgainRes.getResponse().getContentAsString())
                .get("verificationToken").asText();

        mockMvc.perform(post("/api/v1/attendance/kiosk/check-in")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "verificationToken", token2,
                                "idempotencyKey", UUID.randomUUID().toString()
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_MARKED"));

        // Single-round-trip atomic /kiosk/mark works for a different worker (W009) from the same sessionless device
        String w009Idem = UUID.randomUUID().toString();
        mockMvc.perform(post("/api/v1/attendance/kiosk/mark")
                        .with(csrf())
                        .header("Idempotency-Key", w009Idem)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "imageBase64", w009Face,
                                "idempotencyKey", w009Idem
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjectWorkerCode").value("W009"))
                .andExpect(jsonPath("$.subjectWorkerName").value("Deepak Kulkarni"))
                .andExpect(jsonPath("$.attendanceMethod").value("SELF"))
                .andExpect(jsonPath("$.idempotentReplay").value(false));

        // Repeat same /kiosk/mark without same idempotency key includes worker name in ALREADY_MARKED
        mockMvc.perform(post("/api/v1/attendance/kiosk/mark")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "imageBase64", w009Face,
                                "idempotencyKey", UUID.randomUUID().toString()
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_MARKED"))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("Deepak Kulkarni")));

        // Legacy installed PWA holding a WORKER_SESSION cookie gets cleared & revoked automatically on kiosk/config boot
        Cookie inviteCookie = mockMvc.perform(post("/api/v1/admin/workers")
                        .cookie(adminCookie)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "workerCode", "W902",
                                "fullName", "Legacy App User"
                        ))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()
                .transform(json -> {
                    try {
                        String code = objectMapper.readTree(json).get("invitationCode").asText();
                        String face902 = Base64.getEncoder().encodeToString("MOCK_FACE_W902".getBytes(StandardCharsets.UTF_8));
                        return mockMvc.perform(post("/api/v1/workers/register")
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(Map.of(
                                                "invitationCode", code,
                                                "fullName", "Legacy App User",
                                                "consentGiven", true,
                                                "consentVersion", "2026-11-v1",
                                                "imageBase64", face902
                                        ))))
                                .andExpect(status().isOk())
                                .andReturn().getResponse().getCookie(CookieHelper.WORKER_SESSION_COOKIE);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

        MvcResult cfgRes = mockMvc.perform(get("/api/v1/biometrics/config").cookie(inviteCookie))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(cfgRes.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
                .anyMatch(h -> h.contains(CookieHelper.WORKER_SESSION_COOKIE + "=") && h.contains("Max-Age=0"));

        mockMvc.perform(get("/api/v1/workers/me").cookie(inviteCookie))
                .andExpect(status().isUnauthorized());
    }
}
