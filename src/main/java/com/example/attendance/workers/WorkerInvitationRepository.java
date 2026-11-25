package com.example.attendance.workers;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WorkerInvitationRepository extends JpaRepository<WorkerInvitation, UUID> {
    Optional<WorkerInvitation> findByCodeHash(String codeHash);

    Optional<WorkerInvitation> findTopByWorkerIdOrderByCreatedAtDesc(UUID workerId);
}
