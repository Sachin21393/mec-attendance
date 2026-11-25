package com.example.attendance;

import com.example.attendance.attendance.AttendanceCalculator;
import com.example.attendance.attendance.AttendanceMethod;
import com.example.attendance.attendance.AttendanceRecordRepository;
import com.example.attendance.attendance.AttendanceStatus;
import com.example.attendance.auth.CookieHelper;
import com.example.attendance.auth.RateLimiterService;
import com.example.attendance.settings.AttendanceSettings;
import com.example.attendance.settings.AttendanceSettingsRepository;
import com.example.attendance.settings.DayType;
import com.example.attendance.settings.WorkCalendarDay;
import com.example.attendance.settings.WorkCalendarRepository;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
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
class AttendanceCheckInTest {

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

    @Test
    void calculatorEvaluatesShiftBoundariesGraceOvernightAndCutoff() {
        ZoneId ist = ZoneId.of("Asia/Kolkata");
        AttendanceCalculator.EffectiveDayConfig day = new AttendanceCalculator.EffectiveDayConfig(
                LocalDate.of(2026, 11, 25),
                DayType.WORKING,
                LocalTime.of(9, 0),
                15,
                LocalTime.of(18, 0),
                ist,
                null
        );

        // Exactly at 09:00:00 IST (03:30:00 UTC) -> ON_TIME
        assertThat(calculator.evaluateCheckInStatus(Instant.parse("2026-11-25T03:30:00Z"), day))
                .isEqualTo(AttendanceStatus.ON_TIME);

        // Exactly at grace boundary 09:15:00 IST (03:45:00 UTC) -> ON_TIME
        assertThat(calculator.evaluateCheckInStatus(Instant.parse("2026-11-25T03:45:00Z"), day))
                .isEqualTo(AttendanceStatus.ON_TIME);

        // 1 second after grace 09:15:01 IST (03:45:01 UTC) -> LATE
        assertThat(calculator.evaluateCheckInStatus(Instant.parse("2026-11-25T03:45:01Z"), day))
                .isEqualTo(AttendanceStatus.LATE);

        // Before cutoff (17:59 IST = 12:29 UTC) with no record -> NOT_DUE
        assertThat(calculator.deriveDayState(day, null, Instant.parse("2026-11-25T12:29:00Z")))
                .isEqualTo(AttendanceCalculator.DerivedGridState.NOT_DUE);

        // After cutoff (18:01 IST = 12:31 UTC) with no record -> ABSENT
        assertThat(calculator.deriveDayState(day, null, Instant.parse("2026-11-25T12:31:00Z")))
                .isEqualTo(AttendanceCalculator.DerivedGridState.ABSENT);

        // Holiday is never ABSENT even after cutoff
        AttendanceCalculator.EffectiveDayConfig holiday = new AttendanceCalculator.EffectiveDayConfig(
                LocalDate.of(2026, 11, 4), DayType.HOLIDAY, LocalTime.of(9, 0), 15, LocalTime.of(18, 0), ist, "Holiday");
        assertThat(calculator.deriveDayState(holiday, null, Instant.parse("2026-11-25T12:31:00Z")))
                .isEqualTo(AttendanceCalculator.DerivedGridState.HOLIDAY);

        // Overnight shift (22:00 start, 06:00 cutoff): 01:00 IST on Nov 26 belongs to Nov 25 shift
        AttendanceSettings overnight = settingsRepository.findTopByOrderByEffectiveFromUtcDesc().orElseThrow();
        AttendanceSettings copy = new AttendanceSettings() {
            @Override public String getTimezoneId() { return "Asia/Kolkata"; }
            @Override public LocalTime getDefaultShiftStartLocal() { return LocalTime.of(22, 0); }
            @Override public LocalTime getDefaultAbsenceCutoffLocal() { return LocalTime.of(6, 0); }
        };
        assertThat(overnight).isNotNull();
        // 2026-11-26 01:00 IST = 2026-11-25 19:30 UTC
        assertThat(calculator.resolveAttendanceDate(Instant.parse("2026-11-25T19:30:00Z"), copy))
                .isEqualTo(LocalDate.of(2026, 11, 25));
    }

    @Test
    void selfCheckInSupportsIdempotencyAndBlocksSameDayDuplicates() throws Exception {
        String facePayload = Base64.getEncoder().encodeToString("MOCK_FACE_W009".getBytes(StandardCharsets.UTF_8));
        MvcResult regRes = mockMvc.perform(post("/api/v1/workers/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "invitationCode", "JOIN-W009-DEMO",
                                "fullName", "Deepak Kulkarni",
                                "consentGiven", true,
                                "consentVersion", "2026-11-v1",
                                "imageBase64", facePayload
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        Cookie workerCookie = regRes.getResponse().getCookie(CookieHelper.WORKER_SESSION_COOKIE);

        // Before check-in: today shows not marked
        mockMvc.perform(get("/api/v1/attendance/today").cookie(workerCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyMarked").value(false))
                .andExpect(jsonPath("$.attendanceDate").value(workplaceToday.toString()));

        // Verify face -> get short-lived token
        String token1 = obtainSelfToken(workerCookie, facePayload);

        // First check-in succeeds
        MvcResult checkInRes = mockMvc.perform(post("/api/v1/attendance/check-in")
                        .with(csrf())
                        .cookie(workerCookie)
                        .header("Idempotency-Key", "idem-self-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "verificationToken", token1,
                                "idempotencyKey", "idem-self-1"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjectWorkerCode").value("W009"))
                .andExpect(jsonPath("$.attendanceMethod").value(AttendanceMethod.SELF.name()))
                .andExpect(jsonPath("$.idempotentReplay").value(false))
                .andReturn();
        String recordId = objectMapper.readTree(checkInRes.getResponse().getContentAsString()).get("id").asText();

        // Retry with same idempotencyKey returns existing record without creating duplicate
        mockMvc.perform(post("/api/v1/attendance/check-in")
                        .with(csrf())
                        .cookie(workerCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "verificationToken", token1,
                                "idempotencyKey", "idem-self-1"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(recordId))
                .andExpect(jsonPath("$.idempotentReplay").value(true));

        // New attempt on the same day with a fresh verification token and new idempotencyKey is rejected
        String token2 = obtainSelfToken(workerCookie, facePayload);
        mockMvc.perform(post("/api/v1/attendance/check-in")
                        .with(csrf())
                        .cookie(workerCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "verificationToken", token2,
                                "idempotencyKey", "idem-self-2"
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_MARKED"));

        // Today endpoint now reflects alreadyMarked=true
        mockMvc.perform(get("/api/v1/attendance/today").cookie(workerCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyMarked").value(true))
                .andExpect(jsonPath("$.recordId").value(recordId));

        assertThat(attendanceRepository.findBySubjectWorkerIdAndAttendanceDate(
                UUID.fromString("10000000-0000-0000-0000-000000000009"), workplaceToday)).isPresent();
    }

    private String obtainSelfToken(Cookie workerCookie, String facePayload) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/biometrics/verify")
                        .with(csrf())
                        .cookie(workerCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "mode", "SELF",
                                "imageBase64", facePayload
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = objectMapper.readTree(res.getResponse().getContentAsString());
        return json.get("verificationToken").asText();
    }
}
