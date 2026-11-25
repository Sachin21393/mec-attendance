package com.example.attendance.biometrics;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BiometricVerificationTokenRepository extends JpaRepository<BiometricVerificationToken, UUID> {
    Optional<BiometricVerificationToken> findByTokenHash(String tokenHash);
}
