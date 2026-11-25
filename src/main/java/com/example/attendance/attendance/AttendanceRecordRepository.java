package com.example.attendance.attendance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AttendanceRecordRepository extends JpaRepository<AttendanceRecord, UUID> {
    Optional<AttendanceRecord> findBySubjectWorkerIdAndAttendanceDate(UUID subjectWorkerId, LocalDate attendanceDate);

    Optional<AttendanceRecord> findByIdempotencyKey(String idempotencyKey);

    List<AttendanceRecord> findByAttendanceDateBetween(LocalDate start, LocalDate end);

    List<AttendanceRecord> findBySubjectWorkerIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(
            UUID subjectWorkerId, LocalDate start, LocalDate end);

    long countByActorWorkerIdAndAttendanceDateAndAttendanceMethod(
            UUID actorWorkerId, LocalDate attendanceDate, AttendanceMethod method);
}
