package com.example.attendance;

import com.example.attendance.audit.AuditEventRepository;
import com.example.attendance.auth.CookieHelper;
import com.example.attendance.auth.RateLimiterService;
import com.example.attendance.reports.CsvExportUtil;
import com.example.attendance.workers.Worker;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ReportsAndCsvExportTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WorkerRepository workerRepository;

    @Autowired
    private AuditEventRepository auditEventRepository;

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
    void protectsCsvExportAndNeutralizesFormulaInjectionAndUnicodeQuoting() {
        assertThat(CsvExportUtil.escapeCell("=CMD|' /C calc'!A0")).isEqualTo("'=CMD|' /C calc'!A0");
        assertThat(CsvExportUtil.escapeCell("+SUM(A1:A2)")).isEqualTo("'+SUM(A1:A2)");
        assertThat(CsvExportUtil.escapeCell("-1+1")).isEqualTo("'-1+1");
        assertThat(CsvExportUtil.escapeCell("@SUM(1,2)")).isEqualTo("\"'@SUM(1,2)\"");
        assertThat(CsvExportUtil.escapeCell("José, \"Sr.\"")).isEqualTo("\"José, \"\"Sr.\"\"\"");
    }

    @Test
    void exportsUtf8CsvReconcilingWithGridTotalsAndProxyAttribution() throws Exception {
        mockMvc.perform(get("/api/v1/admin/reports/attendance.csv?month=2026-11"))
                .andExpect(status().isUnauthorized());

        // Add synthetic worker with Unicode + comma/quote + formula prefix in team
        workerRepository.saveAndFlush(new Worker(
                UUID.randomUUID(),
                "W901",
                "आशा वर्मा, \"Lead\"",
                "=HYPERLINK(\"http://evil\")",
                WorkerStatus.REGISTERED,
                LocalDate.of(2026, 11, 1),
                null,
                Instant.now()
        ));

        Cookie adminCookie = loginAsAdmin();

        MvcResult gridRes = mockMvc.perform(get("/api/v1/admin/attendance?month=2026-11&size=50").cookie(adminCookie))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode grid = objectMapper.readTree(gridRes.getResponse().getContentAsString());

        MvcResult csvRes = mockMvc.perform(get("/api/v1/admin/reports/attendance.csv?month=2026-11").cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, org.hamcrest.Matchers.containsString("text/csv")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, org.hamcrest.Matchers.containsString("attendance_2026-11.csv")))
                .andReturn();

        String csvText = new String(csvRes.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        String[] lines = csvText.split("\r\n");
        assertThat(lines.length).isEqualTo(grid.get("rows").size() + 1);

        // Formula injection in team column neutralized
        assertThat(csvText).contains("\"'=HYPERLINK(\"\"http://evil\"\")\"");
        // Unicode + quotes properly exported
        assertThat(csvText).contains("\"आशा वर्मा, \"\"Lead\"\"\"");
        // Proxy actor attribution included for W002 (marked by Aarav Sharma (W001) on 2026-11-18)
        assertThat(csvText).contains("Aarav Sharma (W001)");

        // Reconcile per-worker totals between grid JSON and CSV columns 0..9
        for (int i = 0; i < grid.get("rows").size(); i++) {
            JsonNode row = grid.get("rows").get(i);
            String workerCode = row.get("workerCode").asText();
            String matchingLine = java.util.Arrays.stream(lines)
                    .skip(1)
                    .filter(l -> l.startsWith(workerCode + ","))
                    .findFirst()
                    .orElseThrow();
            assertThat(matchingLine).contains(String.format(
                    ",%d,%d,%d,%d,%d,%d,%d,%d,",
                    grid.get("configuredWorkingDays").asInt(),
                    row.get("applicableWorkingDays").asInt(),
                    row.get("workedDays").asInt(),
                    row.get("onTimeDays").asInt(),
                    row.get("lateDays").asInt(),
                    row.get("absentDays").asInt(),
                    row.get("holidayDays").asInt(),
                    row.get("weeklyOffDays").asInt()
            ));
        }

        assertThat(auditEventRepository.findAllByOrderByEventTimestampUtcDesc(PageRequest.of(0, 10))
                .stream().anyMatch(e -> "ATTENDANCE_CSV_EXPORTED".equals(e.getEventType()))).isTrue();
    }
}
