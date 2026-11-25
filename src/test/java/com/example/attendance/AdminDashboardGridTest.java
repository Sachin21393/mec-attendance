package com.example.attendance;

import com.example.attendance.auth.CookieHelper;
import com.example.attendance.auth.RateLimiterService;
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
class AdminDashboardGridTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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
    void protectsAdminDashboardAndGridRoutes() throws Exception {
        mockMvc.perform(get("/api/v1/admin/dashboard?month=2026-11"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/attendance?month=2026-11"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsDashboardKpisPaginatedMonthlyGridAndProxyCellDetails() throws Exception {
        Cookie adminCookie = loginAsAdmin();

        mockMvc.perform(get("/api/v1/admin/dashboard?month=2026-11").cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2026-11"))
                .andExpect(jsonPath("$.configuredWorkingDays").value(24))
                .andExpect(jsonPath("$.activeWorkersCount").value(9))
                .andExpect(jsonPath("$.timezoneId").value("Asia/Kolkata"));

        MvcResult gridRes = mockMvc.perform(get("/api/v1/admin/attendance?month=2026-11&page=0&size=5")
                        .cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days.length()").value(30))
                .andExpect(jsonPath("$.rows.length()").value(5))
                .andExpect(jsonPath("$.totalWorkers").value(10))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andReturn();

        JsonNode grid = objectMapper.readTree(gridRes.getResponse().getContentAsString());
        // W002 has a seeded PROXY record on 2026-11-18 marked by W001
        JsonNode w002 = grid.get("rows").get(1);
        assertThat(w002.get("workerCode").asText()).isEqualTo("W002");
        JsonNode nov18Cell = w002.get("cells").get(17); // 0-indexed day 18
        assertThat(nov18Cell.get("state").asText()).isEqualTo("ON_TIME");
        assertThat(nov18Cell.get("method").asText()).isEqualTo("PROXY");
        assertThat(nov18Cell.get("actorWorkerCode").asText()).isEqualTo("W001");

        String proxyRecordId = nov18Cell.get("recordId").asText();
        mockMvc.perform(get("/api/v1/admin/attendance/" + proxyRecordId).cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjectWorkerCode").value("W002"))
                .andExpect(jsonPath("$.actorWorkerCode").value("W001"))
                .andExpect(jsonPath("$.attendanceMethod").value("PROXY"));

        // Employment boundary check: W010 ended 2026-10-31 so has 0 applicable days in 2026-11
        mockMvc.perform(get("/api/v1/admin/attendance?month=2026-11&query=W010").cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].workerCode").value("W010"))
                .andExpect(jsonPath("$.rows[0].applicableWorkingDays").value(0))
                .andExpect(jsonPath("$.rows[0].cells[0].state").value("NOT_EMPLOYED"));
    }
}
