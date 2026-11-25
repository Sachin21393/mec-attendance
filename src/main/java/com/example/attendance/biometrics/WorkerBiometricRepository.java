package com.example.attendance.biometrics;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WorkerBiometricRepository extends JpaRepository<WorkerBiometric, UUID> {
    Optional<WorkerBiometric> findByWorkerId(UUID workerId);

    void deleteByWorkerId(UUID workerId);
}
