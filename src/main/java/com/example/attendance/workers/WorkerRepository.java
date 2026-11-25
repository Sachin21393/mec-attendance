package com.example.attendance.workers;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkerRepository extends JpaRepository<Worker, UUID> {
    Optional<Worker> findByWorkerCode(String workerCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM Worker w WHERE w.id = :id")
    Optional<Worker> findByIdForUpdate(@Param("id") UUID id);

    List<Worker> findByStatusOrderByWorkerCodeAsc(WorkerStatus status);

    Page<Worker> findByFullNameContainingIgnoreCaseOrWorkerCodeContainingIgnoreCase(
            String fullName, String workerCode, Pageable pageable);
}
