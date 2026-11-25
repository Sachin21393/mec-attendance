package com.example.attendance.attendance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface AttendanceCorrectionRepository extends JpaRepository<AttendanceCorrection, UUID> {
    List<AttendanceCorrection> findBySubjectWorkerIdAndAttendanceDateOrderByCreatedAtDesc(UUID subjectWorkerId, LocalDate attendanceDate);

    List<AttendanceCorrection> findByAttendanceRecordIdOrderByCreatedAtDesc(UUID attendanceRecordId);
}
