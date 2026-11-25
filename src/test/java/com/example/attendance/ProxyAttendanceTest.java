package com.example.attendance;

import com.example.attendance.attendance.AttendanceCalculator;
import com.example.attendance.attendance.AttendanceCheckInService;
import com.example.attendance.attendance.AttendanceMethod;
import com.example.attendance.attendance.AttendanceRecordRepository;
import com.example.attendance.auth.CookieHelper;
import com.example.attendance.auth.RateLimiterService;
import com.example.attendance.biometrics.BiometricsVerificationService;
import com.example.attendance.common.ForbiddenException;
import com.example.attendance.settings.AttendanceSettings;
import com.example.attendance.settings.AttendanceSettingsRepository;
import com.example.attendance.settings.DayType;
import com.example.attendance.settings.WorkCalendarDay;
import com.example.attendance.settings.WorkCalendarRepository;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ProxyAttendanceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AttendanceSettingsRepository settingsRepository;

    @Autowired
    private WorkCalendarRepository calendarRepository;

    @Autowired
    private AttendanceCalculator calculator;

    @Autowired
    private AttendanceRecordRepository attendanceRepository;

    @Autowired
    private BiometricsVerificationService verificationService;

    @Autowired
    private AttendanceCheckInService checkInService;

    @Autowired
    private RateLimiterService rateLimiterService;

    private LocalDate workplaceToday;

    @BeforeEach
    void setup() {
        rateLimiterService.resetAll();
        AttendanceSettings settings = settingsRepository.findTopByOrderByEffectiveFromUtcDesc().orElseThrow();
        workplaceToday = calculator.resolveAttendanceDate(Instant.now(), settings);
        WorkCalendarDay day = calendarRepository.findByCalendarDate(workplaceToday)
                .orElseGet(() -> new WorkCalendarDay(
                        UUID.randomUUID(), workplaceToday, DayType.WORKING,
                        LocalTime.of(9, 0), 15, LocalTime.of(18, 0),
                        settings.getTimezoneId(), "Test Working Day", null, Instant.now()));
        day.setDayType(DayType.WORKING);
        calendarRepository.saveAndFlush(day);
    }

    @Test
    void enforcesProxyCapSelfProxyRejectionDuplicateTargetAndConfigurableLimit() throws Exception {
        String w009Face = b64("MOCK_FACE_W009");
        MvcResult regRes = mockMvc.perform(post("/api/v1/workers/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "invitationCode", "JOIN-W009-DEMO",
                                "fullName", "Deepak Kulkarni",
                                "consentGiven", true,
                                "consentVersion", "2026-11-v1",
                                "imageBase64", w009Face
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        Cookie actorCookie = regRes.getResponse().getCookie(CookieHelper.WORKER_SESSION_COOKIE);

        // Self-proxy rejected even without targetWorkerCode
        mockMvc.perform(post("/api/v1/biometrics/verify")
                        .with(csrf())
                        .cookie(actorCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "mode", "PROXY",
                                "imageBase64", w009Face
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SELF_PROXY_NOT_ALLOWED"));

        // First proxy check-in for W001 succeeds (default cap = 1)
        String tokenW001 = obtainProxyToken(actorCookie, "MOCK_FACE_W001");
        mockMvc.perform(post("/api/v1/attendance/check-in")
                        .with(csrf())
                        .cookie(actorCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "verificationToken", tokenW001,
                                "idempotencyKey", "proxy-w001-1"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjectWorkerCode").value("W001"))
                .andExpect(jsonPath("$.actorWorkerName").value("Deepak Kulkarni"))
                .andExpect(jsonPath("$.attendanceMethod").value(AttendanceMethod.PROXY.name()));

        // Second proxy check-in for W002 rejected under default cap 1
        String tokenW002 = obtainProxyToken(actorCookie, "MOCK_FACE_W002");
        mockMvc.perform(post("/api/v1/attendance/check-in")
                        .with(csrf())
                        .cookie(actorCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "verificationToken", tokenW002,
                                "idempotencyKey", "proxy-w002-1"
                        ))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PROXY_LIMIT_REACHED"));

        // Duplicate target (W001) rejected even if cap raised to 3
        AttendanceSettings settings = settingsRepository.findTopByOrderByEffectiveFromUtcDesc().orElseThrow();
        settings.setMaxProxyPerActorPerDay(3);
        settingsRepository.saveAndFlush(settings);

        String tokenW001Again = obtainProxyToken(actorCookie, "MOCK_FACE_W001");
        mockMvc.perform(post("/api/v1/attendance/check-in")
                        .with(csrf())
                        .cookie(actorCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "verificationToken", tokenW001Again,
                                "idempotencyKey", "proxy-w001-dup"
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_MARKED"));

        // Now W002 succeeds because cap was increased to 3
        mockMvc.perform(post("/api/v1/attendance/check-in")
                        .with(csrf())
                        .cookie(actorCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "verificationToken", tokenW002,
                                "idempotencyKey", "proxy-w002-after-cap-raise"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjectWorkerCode").value("W002"));

        // Disabling proxy mode blocks further proxy verification
        settings.setProxyEnabled(false);
        settingsRepository.saveAndFlush(settings);
        mockMvc.perform(post("/api/v1/biometrics/verify")
                        .with(csrf())
                        .cookie(actorCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "mode", "PROXY",
                                "imageBase64", b64("MOCK_FACE_W003")
                        ))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PROXY_DISABLED"));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentProxyRequestsCannotBypassDailyCap() throws Exception {
        UUID actorId = UUID.fromString("10000000-0000-0000-0000-000000000007"); // W007
        UUID targetA = UUID.fromString("10000000-0000-0000-0000-000000000005"); // W005
        UUID targetB = UUID.fromString("10000000-0000-0000-0000-000000000006"); // W006

        AttendanceSettings settings = settingsRepository.findTopByOrderByEffectiveFromUtcDesc().orElseThrow();
        settings.setProxyEnabled(true);
        settings.setMaxProxyPerActorPerDay(1);
        settingsRepository.saveAndFlush(settings);

        var tokA = verificationService.verify(actorId, BiometricsVerificationService.VerificationMode.PROXY, "W005", b64("MOCK_FACE_W005"));
        var tokB = verificationService.verify(actorId, BiometricsVerificationService.VerificationMode.PROXY, "W006", b64("MOCK_FACE_W006"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        try {
            Callable<Boolean> taskA = () -> {
                startGate.await();
                try {
                    checkInService.checkIn(actorId, null, tokA.verificationToken(), "concurrent-a-" + UUID.randomUUID());
                    return true;
                } catch (ForbiddenException ex) {
                    return false;
                }
            };
            Callable<Boolean> taskB = () -> {
                startGate.await();
                try {
                    checkInService.checkIn(actorId, null, tokB.verificationToken(), "concurrent-b-" + UUID.randomUUID());
                    return true;
                } catch (ForbiddenException ex) {
                    return false;
                }
            };

            Future<Boolean> fa = pool.submit(taskA);
            Future<Boolean> fb = pool.submit(taskB);
            startGate.countDown();

            int successes = (fa.get() ? 1 : 0) + (fb.get() ? 1 : 0);
            assertThat(successes).isEqualTo(1);
            assertThat(attendanceRepository.countByActorWorkerIdAndAttendanceDateAndAttendanceMethod(
                    actorId, workplaceToday, AttendanceMethod.PROXY)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
            for (UUID subjectId : List.of(targetA, targetB)) {
                attendanceRepository.findBySubjectWorkerIdAndAttendanceDate(subjectId, workplaceToday)
                        .ifPresent(attendanceRepository::delete);
            }
        }
    }

    private String obtainProxyToken(Cookie actorCookie, String rawMarker) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/biometrics/verify")
                        .with(csrf())
                        .cookie(actorCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "mode", "PROXY",
                                "imageBase64", b64(rawMarker)
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("verificationToken").asText();
    }

    private static String b64(String raw) {
        return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
