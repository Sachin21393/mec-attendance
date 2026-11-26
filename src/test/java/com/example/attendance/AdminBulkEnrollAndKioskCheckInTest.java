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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

        // Attempting Logout before Login is rejected with NOT_LOGGED_IN
        String face902 = Base64.getEncoder().encodeToString("MOCK_FACE_W902".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(post("/api/v1/attendance/kiosk/mark")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "imageBase64", face902,
                                "actionType", "LOGOUT",
                                "idempotencyKey", UUID.randomUUID().toString()
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_LOGGED_IN"));

        // Configure shiftEndLocal = 23:59 -> W009 logout now evaluates to EARLY_LOGOUT ("Early Logout")
        mockMvc.perform(put("/api/v1/admin/settings")
                        .cookie(adminCookie)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "workplaceName", "MEC Team",
                                "timezoneId", "Asia/Kolkata",
                                "shiftStartLocal", "00:00",
                                "gracePeriodMinutes", 15,
                                "absenceCutoffLocal", "23:59",
                                "shiftEndLocal", "23:59",
                                "proxyEnabled", true,
                                "maxProxyPerActorPerDay", 1
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shiftEndLocal").value("23:59:00"));

        mockMvc.perform(post("/api/v1/attendance/kiosk/mark")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "imageBase64", w009Face,
                                "actionType", "LOGOUT",
                                "idempotencyKey", UUID.randomUUID().toString()
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actionType").value("LOGOUT"))
                .andExpect(jsonPath("$.subjectWorkerCode").value("W009"))
                .andExpect(jsonPath("$.logoutStatus").value("EARLY_LOGOUT"))
                .andExpect(jsonPath("$.logoutStatusLabel").value("Early Logout"));

        // Second logout on same day rejects with ALREADY_LOGGED_OUT
        mockMvc.perform(post("/api/v1/attendance/kiosk/mark")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "imageBase64", w009Face,
                                "actionType", "LOGOUT",
                                "idempotencyKey", UUID.randomUUID().toString()
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_LOGGED_OUT"));

        // Configure shiftEndLocal = 00:01 -> W901 logout now evaluates to EXTENDED_WORK_HOURS ("Extended Work Hours")
        mockMvc.perform(put("/api/v1/admin/settings")
                        .cookie(adminCookie)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "workplaceName", "MEC Team",
                                "timezoneId", "Asia/Kolkata",
                                "shiftStartLocal", "00:00",
                                "gracePeriodMinutes", 15,
                                "absenceCutoffLocal", "23:59",
                                "shiftEndLocal", "00:01",
                                "proxyEnabled", true,
                                "maxProxyPerActorPerDay", 1
                        ))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/attendance/kiosk/mark")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "imageBase64", w901Face,
                                "actionType", "LOGOUT",
                                "idempotencyKey", UUID.randomUUID().toString()
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actionType").value("LOGOUT"))
                .andExpect(jsonPath("$.subjectWorkerCode").value("W901"))
                .andExpect(jsonPath("$.logoutStatus").value("EXTENDED_WORK_HOURS"))
                .andExpect(jsonPath("$.logoutStatusLabel").value("Extended Work Hours"));

        String csvContent = mockMvc.perform(get("/api/v1/admin/reports/attendance.csv?month=" + workplaceToday.toString().substring(0, 7))
                        .cookie(adminCookie))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(csvContent).contains("Early Logout", "Extended Work Hours");
    }

    @Test
    void geofenceBlocksOutsideZoneAndAllowsConfiguredRadius() throws Exception {
        Cookie adminCookie = loginAsAdmin();
        AttendanceSettings settings = settingsRepository.findTopByOrderByEffectiveFromUtcDesc().orElseThrow();
        LocalDate workplaceToday = calculator.resolveAttendanceDate(Instant.now(), settings);
        calendarRepository.findByCalendarDate(workplaceToday).ifPresentOrElse(day -> {
            day.setDayType(DayType.WORKING);
            day.setShiftStartLocal(LocalTime.of(0, 0));
            day.setGracePeriodMinutes(15);
            day.setAbsenceCutoffLocal(LocalTime.of(23, 59));
            day.setShiftEndLocal(LocalTime.of(23, 59));
            calendarRepository.save(day);
        }, () -> calendarRepository.save(new WorkCalendarDay(
                UUID.randomUUID(), workplaceToday, DayType.WORKING,
                LocalTime.of(0, 0), 15, LocalTime.of(23, 59), LocalTime.of(23, 59),
                settings.getTimezoneId(), "Test workday", null, Instant.now()
        )));

        String face903 = Base64.getEncoder().encodeToString("MOCK_FACE_W903".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(post("/api/v1/admin/workers/enroll")
                        .cookie(adminCookie)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "workerCode", "W903",
                                "fullName", "Geofence Test Worker",
                                "teamName", "QA",
                                "consentGiven", true,
                                "imageBase64", face903
                        ))))
                .andExpect(status().isCreated());

        // Enabling geofencing without coordinates is rejected
        mockMvc.perform(put("/api/v1/admin/settings")
                        .cookie(adminCookie)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.ofEntries(
                                Map.entry("workplaceName", "MEC Team"),
                                Map.entry("timezoneId", "Asia/Kolkata"),
                                Map.entry("shiftStartLocal", "00:00"),
                                Map.entry("gracePeriodMinutes", 15),
                                Map.entry("absenceCutoffLocal", "23:59"),
                                Map.entry("proxyEnabled", true),
                                Map.entry("maxProxyPerActorPerDay", 1),
                                Map.entry("geofenceEnabled", true),
                                Map.entry("geofenceRadiusMeters", 50)
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GEOFENCE_COORDINATES_REQUIRED"));

        // Radius smaller than 5 meters is rejected
        mockMvc.perform(put("/api/v1/admin/settings")
                        .cookie(adminCookie)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.ofEntries(
                                Map.entry("workplaceName", "MEC Team"),
                                Map.entry("timezoneId", "Asia/Kolkata"),
                                Map.entry("shiftStartLocal", "00:00"),
                                Map.entry("gracePeriodMinutes", 15),
                                Map.entry("absenceCutoffLocal", "23:59"),
                                Map.entry("proxyEnabled", true),
                                Map.entry("maxProxyPerActorPerDay", 1),
                                Map.entry("geofenceEnabled", true),
                                Map.entry("geofenceLatitude", 19.076090),
                                Map.entry("geofenceLongitude", 72.877426),
                                Map.entry("geofenceRadiusMeters", 2)
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_GEOFENCE_RADIUS"));

        // Configure 50m office zone at (19.076090, 72.877426)
        mockMvc.perform(put("/api/v1/admin/settings")
                        .cookie(adminCookie)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.ofEntries(
                                Map.entry("workplaceName", "MEC Team"),
                                Map.entry("timezoneId", "Asia/Kolkata"),
                                Map.entry("shiftStartLocal", "00:00"),
                                Map.entry("gracePeriodMinutes", 15),
                                Map.entry("absenceCutoffLocal", "23:59"),
                                Map.entry("shiftEndLocal", "23:59"),
                                Map.entry("proxyEnabled", true),
                                Map.entry("maxProxyPerActorPerDay", 1),
                                Map.entry("geofenceEnabled", true),
                                Map.entry("geofenceLatitude", 19.076090),
                                Map.entry("geofenceLongitude", 72.877426),
                                Map.entry("geofenceRadiusMeters", 50)
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.geofenceEnabled").value(true))
                .andExpect(jsonPath("$.geofenceRadiusMeters").value(50));

        mockMvc.perform(get("/api/v1/attendance/kiosk/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.geofenceEnabled").value(true))
                .andExpect(jsonPath("$.geofenceRadiusMeters").value(50));

        // Missing location fails with LOCATION_REQUIRED
        mockMvc.perform(post("/api/v1/attendance/kiosk/mark")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "imageBase64", face903,
                                "actionType", "LOGIN",
                                "idempotencyKey", UUID.randomUUID().toString()
                        ))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("LOCATION_REQUIRED"));

        // ~150m outside 50m radius fails both pre-check and kiosk/mark with OUT_OF_OFFICE_ZONE
        mockMvc.perform(post("/api/v1/attendance/kiosk/location-check")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "latitude", 19.077440,
                                "longitude", 72.877426
                        ))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("OUT_OF_OFFICE_ZONE"));

        mockMvc.perform(post("/api/v1/attendance/kiosk/mark")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "imageBase64", face903,
                                "actionType", "LOGIN",
                                "idempotencyKey", UUID.randomUUID().toString(),
                                "latitude", 19.077440,
                                "longitude", 72.877426
                        ))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("OUT_OF_OFFICE_ZONE"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("You are out of the office zone")));

        // ~18m inside 50m radius passes location-check, LOGIN, and LOGOUT
        mockMvc.perform(post("/api/v1/attendance/kiosk/location-check")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "latitude", 19.076250,
                                "longitude", 72.877426
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.radiusMeters").value(50));

        mockMvc.perform(post("/api/v1/attendance/kiosk/mark")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "imageBase64", face903,
                                "actionType", "LOGIN",
                                "idempotencyKey", UUID.randomUUID().toString(),
                                "latitude", 19.076250,
                                "longitude", 72.877426
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjectWorkerCode").value("W903"))
                .andExpect(jsonPath("$.distanceMeters").isNumber());

        // Logout while outside zone is also blocked
        mockMvc.perform(post("/api/v1/attendance/kiosk/mark")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "imageBase64", face903,
                                "actionType", "LOGOUT",
                                "idempotencyKey", UUID.randomUUID().toString(),
                                "latitude", 19.077440,
                                "longitude", 72.877426
                        ))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("OUT_OF_OFFICE_ZONE"));

        // Expanding radius to 200m in Settings allows the ~150m coordinate
        mockMvc.perform(put("/api/v1/admin/settings")
                        .cookie(adminCookie)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.ofEntries(
                                Map.entry("workplaceName", "MEC Team"),
                                Map.entry("timezoneId", "Asia/Kolkata"),
                                Map.entry("shiftStartLocal", "00:00"),
                                Map.entry("gracePeriodMinutes", 15),
                                Map.entry("absenceCutoffLocal", "23:59"),
                                Map.entry("shiftEndLocal", "23:59"),
                                Map.entry("proxyEnabled", true),
                                Map.entry("maxProxyPerActorPerDay", 1),
                                Map.entry("geofenceEnabled", true),
                                Map.entry("geofenceLatitude", 19.076090),
                                Map.entry("geofenceLongitude", 72.877426),
                                Map.entry("geofenceRadiusMeters", 200)
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.geofenceRadiusMeters").value(200));

        mockMvc.perform(post("/api/v1/attendance/kiosk/mark")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "imageBase64", face903,
                                "actionType", "LOGOUT",
                                "idempotencyKey", UUID.randomUUID().toString(),
                                "latitude", 19.077440,
                                "longitude", 72.877426
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actionType").value("LOGOUT"))
                .andExpect(jsonPath("$.subjectWorkerCode").value("W903"));

        // Disabling geofencing via settings removes location restriction while keeping saved coordinates
        mockMvc.perform(put("/api/v1/admin/settings")
                        .cookie(adminCookie)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.ofEntries(
                                Map.entry("workplaceName", "MEC Team"),
                                Map.entry("timezoneId", "Asia/Kolkata"),
                                Map.entry("shiftStartLocal", "00:00"),
                                Map.entry("gracePeriodMinutes", 15),
                                Map.entry("absenceCutoffLocal", "23:59"),
                                Map.entry("shiftEndLocal", "23:59"),
                                Map.entry("proxyEnabled", true),
                                Map.entry("maxProxyPerActorPerDay", 1),
                                Map.entry("geofenceEnabled", false)
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.geofenceEnabled").value(false))
                .andExpect(jsonPath("$.geofenceLatitude").value(19.076090));

        mockMvc.perform(get("/api/v1/attendance/kiosk/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.geofenceEnabled").value(false));

        mockMvc.perform(post("/api/v1/attendance/kiosk/location-check")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "latitude", 19.077440,
                                "longitude", 72.877426
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.geofenceEnabled").value(false));
    }
}
