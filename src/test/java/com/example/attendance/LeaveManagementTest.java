package com.example.attendance;

import com.example.attendance.attendance.AttendanceCalculator;
import com.example.attendance.auth.CookieHelper;
import com.example.attendance.auth.RateLimiterService;
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
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

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
class LeaveManagementTest {

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
    private RateLimiterService rateLimiterService;

    private LocalDate workplaceToday;

    @BeforeEach
    void setup() {
        rateLimiterService.resetAll();
        AttendanceSettings settings = settingsRepository.findTopByOrderByEffectiveFromUtcDesc().orElseThrow();
        workplaceToday = calculator.resolveAttendanceDate(Instant.now(), settings);
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
                settings.getTimezoneId(), "Leave Test Day", null, Instant.now()
        )));
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
    void supportsSelfLeaveViaFaceScanQuotasAndAutoLeaveAfterCutoff() throws Exception {
        Cookie adminCookie = loginAsAdmin();

        // Configure leave quotas (10 Paid/Sick, 15 Annual) + auto-leave cutoff off briefly for self-mark tests
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
                                Map.entry("paidLeaveQuota", 10),
                                Map.entry("annualLeaveQuota", 15),
                                Map.entry("autoMarkLeaveAfterCutoff", true)
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paidLeaveQuota").value(10))
                .andExpect(jsonPath("$.annualLeaveQuota").value(15))
                .andExpect(jsonPath("$.autoMarkLeaveAfterCutoff").value(true));

        String face910 = Base64.getEncoder().encodeToString("MOCK_FACE_W910".getBytes(StandardCharsets.UTF_8));
        String face911 = Base64.getEncoder().encodeToString("MOCK_FACE_W911".getBytes(StandardCharsets.UTF_8));
        String face912 = Base64.getEncoder().encodeToString("MOCK_FACE_W912".getBytes(StandardCharsets.UTF_8));

        for (Map.Entry<String, String> emp : Map.of("W910", face910, "W911", face911, "W912", face912).entrySet()) {
            mockMvc.perform(post("/api/v1/admin/workers/enroll")
                            .cookie(adminCookie)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "workerCode", emp.getKey(),
                                    "fullName", "Employee " + emp.getKey(),
                                    "teamName", "Engineering",
                                    "consentGiven", true,
                                    "imageBase64", emp.getValue()
                            ))))
                    .andExpect(status().isCreated());
        }

        // 1) W910 marks Sick / Paid Leave via face scan -> deducted from Paid/Sick quota
        mockMvc.perform(post("/api/v1/attendance/kiosk/mark-leave")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "imageBase64", face910,
                                "leaveType", "SICK_LEAVE",
                                "idempotencyKey", UUID.randomUUID().toString()
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employeeCode").value("W910"))
                .andExpect(jsonPath("$.leaveType").value("SICK_LEAVE"))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.paidLeaveTaken").value(1))
                .andExpect(jsonPath("$.paidLeavePending").value(9))
                .andExpect(jsonPath("$.annualLeaveTaken").value(0))
                .andExpect(jsonPath("$.annualLeavePending").value(15));

        // Duplicate leave on same day is rejected
        mockMvc.perform(post("/api/v1/attendance/kiosk/mark-leave")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "imageBase64", face910,
                                "leaveType", "ANNUAL_LEAVE",
                                "idempotencyKey", UUID.randomUUID().toString()
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEAVE_ALREADY_MARKED"));

        // W910 cannot mark Login Attendance after taking leave today
        mockMvc.perform(post("/api/v1/attendance/kiosk/mark")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "imageBase64", face910,
                                "actionType", "LOGIN",
                                "idempotencyKey", UUID.randomUUID().toString()
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEAVE_ALREADY_MARKED"));

        // 2) W911 marks Login Attendance first -> cannot subsequently mark Leave today
        mockMvc.perform(post("/api/v1/attendance/kiosk/mark")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "imageBase64", face911,
                                "actionType", "LOGIN",
                                "idempotencyKey", UUID.randomUUID().toString()
                        ))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/attendance/kiosk/mark-leave")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "imageBase64", face911,
                                "leaveType", "ANNUAL_LEAVE",
                                "idempotencyKey", UUID.randomUUID().toString()
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_MARKED"));

        // 3) Advance cutoff past now (00:01) and check W912 (no attendance & no self-leave) is auto-marked as Leave
        AttendanceSettings s = settingsRepository.findTopByOrderByEffectiveFromUtcDesc().orElseThrow();
        s.setAutoLeaveEffectiveFromDate(workplaceToday);
        settingsRepository.saveAndFlush(s);

        mockMvc.perform(put("/api/v1/admin/settings")
                        .cookie(adminCookie)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.ofEntries(
                                Map.entry("workplaceName", "MEC Team"),
                                Map.entry("timezoneId", "Asia/Kolkata"),
                                Map.entry("shiftStartLocal", "00:00"),
                                Map.entry("gracePeriodMinutes", 15),
                                Map.entry("absenceCutoffLocal", "00:01"),
                                Map.entry("shiftEndLocal", "23:59"),
                                Map.entry("proxyEnabled", true),
                                Map.entry("maxProxyPerActorPerDay", 1),
                                Map.entry("paidLeaveQuota", 10),
                                Map.entry("annualLeaveQuota", 15),
                                Map.entry("autoMarkLeaveAfterCutoff", true)
                        ))))
                .andExpect(status().isOk());

        String currentMonth = workplaceToday.toString().substring(0, 7);

        // Admin Leaves menu endpoint triggers auto-cutoff & shows W910 (Paid/Sick=1) and W912 (Auto Annual=1) + totalWorkingDays
        mockMvc.perform(get("/api/v1/admin/leaves?month=" + currentMonth + "&query=W912")
                        .cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalWorkingDays").isNumber())
                .andExpect(jsonPath("$.paidLeaveQuota").value(10))
                .andExpect(jsonPath("$.annualLeaveQuota").value(15))
                .andExpect(jsonPath("$.employees[0].workerCode").value("W912"))
                .andExpect(jsonPath("$.employees[0].annualLeaveTaken").value(1))
                .andExpect(jsonPath("$.employees[0].annualLeavePending").value(14))
                .andExpect(jsonPath("$.employees[0].totalPendingRemaining").value(24))
                .andExpect(jsonPath("$.entries[0].source").value("AUTO_CUTOFF"));

        // Dashboard shows leave counts & pending balances
        mockMvc.perform(get("/api/v1/admin/dashboard?month=" + currentMonth)
                        .cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leavesTodayCount").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.totalApprovedLeavesCount").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.totalPendingLeavesCount").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }
}
