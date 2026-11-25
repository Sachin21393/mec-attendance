package com.example.attendance;

import com.example.attendance.attendance.AttendanceRecordRepository;
import com.example.attendance.audit.AuditEventRepository;
import com.example.attendance.auth.AdminRole;
import com.example.attendance.auth.AdminUser;
import com.example.attendance.auth.AdminUserRepository;
import com.example.attendance.auth.CookieHelper;
import com.example.attendance.auth.RateLimiterService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
class SettingsAndCalendarTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AdminUserRepository adminUserRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AttendanceRecordRepository attendanceRepository;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Autowired
    private RateLimiterService rateLimiterService;

    @BeforeEach
    void setup() {
        rateLimiterService.resetAll();
    }

    private Cookie login(String username, String password) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/admin/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", password
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return res.getResponse().getCookie(CookieHelper.ADMIN_SESSION_COOKIE);
    }

    @Test
    void configuresCustom20And30WorkingDayMonthsAndRecalculatesWithoutDeletingRecords() throws Exception {
        Cookie techCookie = login("admin", "TestAdminPassword123!");
        long initialRecordCount = attendanceRepository.count();

        // Configure 2026-11 to have 30 working days (no weekly offs, no holidays)
        mockMvc.perform(put("/api/v1/admin/calendar?month=2026-11")
                        .with(csrf())
                        .cookie(techCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "weeklyOffDays", List.of()
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workingDaysCount").value(30));

        mockMvc.perform(get("/api/v1/admin/dashboard?month=2026-11").cookie(techCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configuredWorkingDays").value(30));

        // Configure 2026-11 to exactly 20 working days (days 21..30 as HOLIDAY)
        List<Map<String, Object>> days = new ArrayList<>();
        for (int d = 1; d <= 30; d++) {
            days.add(Map.of(
                    "date", String.format("2026-11-%02d", d),
                    "dayType", d <= 20 ? "WORKING" : "HOLIDAY"
            ));
        }
        mockMvc.perform(put("/api/v1/admin/calendar?month=2026-11")
                        .with(csrf())
                        .cookie(techCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("days", days))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workingDaysCount").value(20));

        mockMvc.perform(get("/api/v1/admin/attendance?month=2026-11").cookie(techCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configuredWorkingDays").value(20));

        // Historical attendance records are never deleted when calendar changes
        assertThat(attendanceRepository.count()).isEqualTo(initialRecordCount);

        // Copy weekly pattern from 2026-11 (after setting Sun+Sat off) into 2026-12
        mockMvc.perform(put("/api/v1/admin/calendar?month=2026-11")
                        .with(csrf())
                        .cookie(techCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "weeklyOffDays", List.of("SATURDAY", "SUNDAY")
                        ))))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/admin/calendar?month=2026-12")
                        .with(csrf())
                        .cookie(techCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "copyFromMonth", "2026-11"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2026-12"))
                .andExpect(jsonPath("$.workingDaysCount").value(23));
    }

    @Test
    void validatesSettingsAndRestrictsBiometricThresholdToTechAdmin() throws Exception {
        // Create an ordinary ADMIN user
        adminUserRepository.saveAndFlush(new AdminUser(
                UUID.randomUUID(),
                "ops_admin",
                "Operations Admin",
                passwordEncoder.encode("OpsPass123!"),
                AdminRole.ADMIN,
                Instant.now()
        ));
        Cookie opsCookie = login("ops_admin", "OpsPass123!");
        Cookie techCookie = login("admin", "TestAdminPassword123!");

        // Invalid timezone rejected
        mockMvc.perform(put("/api/v1/admin/settings")
                        .with(csrf())
                        .cookie(opsCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "workplaceName", "Plant A",
                                "timezoneId", "Invalid/Zone",
                                "shiftStartLocal", "09:00",
                                "gracePeriodMinutes", 15,
                                "absenceCutoffLocal", "18:00",
                                "proxyEnabled", true,
                                "maxProxyPerActorPerDay", 1
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TIMEZONE"));

        // Ordinary ADMIN cannot alter faceMatchThreshold
        mockMvc.perform(put("/api/v1/admin/settings")
                        .with(csrf())
                        .cookie(opsCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "workplaceName", "Plant A",
                                "timezoneId", "Asia/Kolkata",
                                "shiftStartLocal", "09:00",
                                "gracePeriodMinutes", 15,
                                "absenceCutoffLocal", "18:00",
                                "proxyEnabled", true,
                                "maxProxyPerActorPerDay", 2,
                                "faceMatchThreshold", 0.45
                        ))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TECH_ADMIN_REQUIRED"));

        // TECH_ADMIN can update threshold and settings, and audit event is written
        mockMvc.perform(put("/api/v1/admin/settings")
                        .with(csrf())
                        .cookie(techCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "workplaceName", "Metro Plant B",
                                "timezoneId", "Asia/Kolkata",
                                "shiftStartLocal", "08:30",
                                "gracePeriodMinutes", 10,
                                "absenceCutoffLocal", "17:30",
                                "proxyEnabled", true,
                                "maxProxyPerActorPerDay", 3,
                                "faceMatchThreshold", 0.42
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workplaceName").value("Metro Plant B"))
                .andExpect(jsonPath("$.maxProxyPerActorPerDay").value(3))
                .andExpect(jsonPath("$.faceMatchThreshold").value(0.42));

        assertThat(auditEventRepository.findAllByOrderByEventTimestampUtcDesc(PageRequest.of(0, 10))
                .stream().anyMatch(e -> "SETTINGS_UPDATED".equals(e.getEventType()))).isTrue();
    }
}
