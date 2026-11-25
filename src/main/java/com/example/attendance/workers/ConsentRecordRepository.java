package com.example.attendance.workers;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ConsentRecordRepository extends JpaRepository<ConsentRecord, UUID> {
    List<ConsentRecord> findByWorkerIdOrderByRecordedAtUtcDesc(UUID workerId);
}
