package com.example.attendance;

import com.example.attendance.attendance.AttendanceMethod;
import com.example.attendance.attendance.AttendanceRecord;
import com.example.attendance.attendance.AttendanceRecordRepository;
import com.example.attendance.attendance.AttendanceStatus;
import com.example.attendance.workers.Worker;
import com.example.attendance.workers.WorkerRepository;
import com.example.attendance.workers.WorkerStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class AttendanceRepositoryTest {

    @Autowired
    private AttendanceRecordRepository attendanceRepository;

    @Autowired
    private WorkerRepository workerRepository;

    @Test
    void enforcesOneAttendancePerWorkerPerAttendanceDay() {
        UUID workerId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        LocalDate date = LocalDate.of(2026, 11, 25);
        Instant now = Instant.parse("2026-11-25T03:30:00Z");

        attendanceRepository.saveAndFlush(new AttendanceRecord(
                UUID.randomUUID(), workerId, workerId, date, now,
                AttendanceStatus.ON_TIME, AttendanceMethod.SELF, "idem-1", 0.95, null, null, now));

        AttendanceRecord duplicate = new AttendanceRecord(
                UUID.randomUUID(), workerId, workerId, date, now.plusSeconds(60),
                AttendanceStatus.ON_TIME, AttendanceMethod.SELF, "idem-2", 0.95, null, null, now);

        assertThatThrownBy(() -> attendanceRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void enforcesUniqueWorkerCode() {
        Instant now = Instant.now();
        Worker duplicateCode = new Worker(
                UUID.randomUUID(), "W001", "Duplicate Code", "Assembly",
                WorkerStatus.INVITED, LocalDate.of(2026, 11, 25), null, now);

        assertThatThrownBy(() -> workerRepository.saveAndFlush(duplicateCode))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsMultipleNullIdempotencyKeysAndEnforcesUniqueNonNull() {
        Instant now = Instant.now();
        UUID w5 = UUID.fromString("10000000-0000-0000-0000-000000000005");
        UUID w6 = UUID.fromString("10000000-0000-0000-0000-000000000006");
        UUID w7 = UUID.fromString("10000000-0000-0000-0000-000000000007");
        LocalDate date = LocalDate.of(2026, 11, 25);

        attendanceRepository.saveAndFlush(new AttendanceRecord(
                UUID.randomUUID(), w5, w5, date, now,
                AttendanceStatus.ON_TIME, AttendanceMethod.SELF, null, 0.9, null, null, now));
        attendanceRepository.saveAndFlush(new AttendanceRecord(
                UUID.randomUUID(), w6, w6, date, now,
                AttendanceStatus.ON_TIME, AttendanceMethod.SELF, null, 0.9, null, null, now));

        assertThat(attendanceRepository.findBySubjectWorkerIdAndAttendanceDate(w5, date)).isPresent();
        assertThat(attendanceRepository.findBySubjectWorkerIdAndAttendanceDate(w6, date)).isPresent();

        attendanceRepository.saveAndFlush(new AttendanceRecord(
                UUID.randomUUID(), w7, w7, date, now,
                AttendanceStatus.ON_TIME, AttendanceMethod.SELF, "unique-key-1", 0.9, null, null, now));

        UUID w8 = UUID.fromString("10000000-0000-0000-0000-000000000008");
        AttendanceRecord duplicateIdem = new AttendanceRecord(
                UUID.randomUUID(), w8, w8, date, now,
                AttendanceStatus.ON_TIME, AttendanceMethod.SELF, "unique-key-1", 0.9, null, null, now);

        assertThatThrownBy(() -> attendanceRepository.saveAndFlush(duplicateIdem))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
