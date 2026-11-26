package com.example.attendance;

import com.example.attendance.attendance.AttendanceRecordRepository;
import com.example.attendance.audit.AuditEventRepository;
import com.example.attendance.settings.AttendanceSettingsRepository;
import com.example.attendance.settings.WorkCalendarRepository;
import com.example.attendance.workers.WorkerRepository;
import com.example.attendance.workers.WorkerStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FlywaySchemaMigrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WorkerRepository workerRepository;

    @Autowired
    private AttendanceSettingsRepository settingsRepository;

    @Autowired
    private WorkCalendarRepository calendarRepository;

    @Autowired
    private AttendanceRecordRepository attendanceRepository;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Test
    void healthEndpointAndOpenApiRespondOk() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("MECATT Team Attendance API"));
    }

    @Test
    void syntheticSeedDataIsLoadedCleanly() {
        assertThat(settingsRepository.findTopByOrderByEffectiveFromUtcDesc()).isPresent();
        assertThat(workerRepository.count()).isEqualTo(10);
        assertThat(workerRepository.findByStatusOrderByWorkerCodeAsc(WorkerStatus.REGISTERED)).hasSize(8);
        assertThat(workerRepository.findByStatusOrderByWorkerCodeAsc(WorkerStatus.INVITED)).hasSize(1);
        assertThat(workerRepository.findByStatusOrderByWorkerCodeAsc(WorkerStatus.DISABLED)).hasSize(1);
        assertThat(calendarRepository.findByCalendarDateBetweenOrderByCalendarDateAsc(
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30))).hasSize(30);
        assertThat(attendanceRepository.count()).isEqualTo(12);
        assertThat(auditEventRepository.count()).isGreaterThanOrEqualTo(3);
    }
}
