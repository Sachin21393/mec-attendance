package com.example.attendance.workers;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkerRepository extends JpaRepository<Worker, UUID> {
    Optional<Worker> findByWorkerCode(String workerCode);

    List<Worker> findByStatusOrderByWorkerCodeAsc(WorkerStatus status);

    Page<Worker> findByFullNameContainingIgnoreCaseOrWorkerCodeContainingIgnoreCase(
            String fullName, String workerCode, Pageable pageable);
}
