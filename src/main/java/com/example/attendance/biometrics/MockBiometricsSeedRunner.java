package com.example.attendance.biometrics;

import com.example.attendance.common.AttendanceProperties;
import com.example.attendance.workers.Worker;
import com.example.attendance.workers.WorkerRepository;
import com.example.attendance.workers.WorkerStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Enrolls deterministic synthetic mock templates for seeded REGISTERED workers in dev/test mode.
 * Contains no real biometric data and runs only when mock provider + bootstrap are active.
 */
@Component
public class MockBiometricsSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MockBiometricsSeedRunner.class);

    private final AttendanceProperties properties;
    private final FaceRecognitionProvider faceProvider;
    private final WorkerRepository workerRepository;
    private final WorkerBiometricRepository biometricRepository;
    private final BiometricEncryptionService encryptionService;

    public MockBiometricsSeedRunner(
            AttendanceProperties properties,
            FaceRecognitionProvider faceProvider,
            WorkerRepository workerRepository,
            WorkerBiometricRepository biometricRepository,
            BiometricEncryptionService encryptionService
    ) {
        this.properties = properties;
        this.faceProvider = faceProvider;
        this.workerRepository = workerRepository;
        this.biometricRepository = biometricRepository;
        this.encryptionService = encryptionService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.getBootstrap().isEnabled() || !faceProvider.isMock()) {
            return;
        }
        List<Worker> registered = workerRepository.findByStatusOrderByWorkerCodeAsc(WorkerStatus.REGISTERED);
        int enrolledCount = 0;
        Instant now = Instant.now();
        for (Worker worker : registered) {
            if (biometricRepository.findByWorkerId(worker.getId()).isPresent()) {
                continue;
            }
            byte[] syntheticPayload = ("MOCK_FACE_" + worker.getWorkerCode()).getBytes(StandardCharsets.UTF_8);
            FaceRecognitionProvider.ExtractionResult res = faceProvider.extractEmbedding(syntheticPayload);
            BiometricEncryptionService.EncryptedTemplate enc = encryptionService.encrypt(res.embedding());
            biometricRepository.save(new WorkerBiometric(
                    UUID.randomUUID(),
                    worker.getId(),
                    enc.ciphertext(),
                    enc.iv(),
                    enc.keyVersion(),
                    faceProvider.modelName(),
                    faceProvider.modelVersion(),
                    faceProvider.embeddingDimension(),
                    BiometricsConfigController.CURRENT_CONSENT_VERSION,
                    now
            ));
            enrolledCount++;
        }
        if (enrolledCount > 0) {
            log.info("Enrolled deterministic synthetic mock templates for {} seeded REGISTERED workers.", enrolledCount);
        }
    }
}
