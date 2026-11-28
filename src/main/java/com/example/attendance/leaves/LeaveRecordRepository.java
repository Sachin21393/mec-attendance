package com.example.attendance.leaves;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LeaveRecordRepository extends JpaRepository<LeaveRecord, UUID> {

    Optional<LeaveRecord> findByWorkerIdAndLeaveDate(UUID workerId, LocalDate leaveDate);

    Optional<LeaveRecord> findByIdempotencyKey(String idempotencyKey);

    List<LeaveRecord> findByLeaveDateBetween(LocalDate startDate, LocalDate endDate);

    List<LeaveRecord> findByLeaveDateBetweenOrderByLeaveDateDescCreatedAtDesc(LocalDate startDate, LocalDate endDate);

    List<LeaveRecord> findByWorkerIdOrderByLeaveDateDesc(UUID workerId);

    long countByWorkerIdAndLeaveType(UUID workerId, LeaveType leaveType);

    void deleteByWorkerIdAndLeaveDate(UUID workerId, LocalDate leaveDate);

    void deleteByWorkerIdIn(Iterable<UUID> workerIds);
}
