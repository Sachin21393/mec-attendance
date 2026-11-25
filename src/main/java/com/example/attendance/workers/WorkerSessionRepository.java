package com.example.attendance.workers;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkerSessionRepository extends JpaRepository<WorkerSession, UUID> {
    Optional<WorkerSession> findByTokenHash(String tokenHash);

    List<WorkerSession> findByWorkerIdAndRevokedAtUtcIsNull(UUID workerId);
}
