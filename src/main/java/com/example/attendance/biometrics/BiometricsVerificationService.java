package com.example.attendance.biometrics;

import com.example.attendance.audit.AuditActorType;
import com.example.attendance.audit.AuditService;
import com.example.attendance.auth.TokenHashUtil;
import com.example.attendance.common.AppException;
import com.example.attendance.common.AttendanceProperties;
import com.example.attendance.common.ForbiddenException;
import com.example.attendance.common.ResourceNotFoundException;
import com.example.attendance.common.ValidationException;
import com.example.attendance.settings.AttendanceSettings;
import com.example.attendance.settings.AttendanceSettingsRepository;
import com.example.attendance.workers.Worker;
import com.example.attendance.workers.WorkerRepository;
import com.example.attendance.workers.WorkerStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class BiometricsVerificationService {

    private final FaceRecognitionProvider faceProvider;
    private final BiometricEncryptionService encryptionService;
    private final ImagePayloadValidator imageValidator;
    private final WorkerBiometricRepository biometricRepository;
    private final WorkerRepository workerRepository;
    private final BiometricVerificationTokenRepository tokenRepository;
    private final AttendanceSettingsRepository settingsRepository;
    private final AttendanceProperties properties;
    private final AuditService auditService;

    public BiometricsVerificationService(
            FaceRecognitionProvider faceProvider,
            BiometricEncryptionService encryptionService,
            ImagePayloadValidator imageValidator,
            WorkerBiometricRepository biometricRepository,
            WorkerRepository workerRepository,
            BiometricVerificationTokenRepository tokenRepository,
            AttendanceSettingsRepository settingsRepository,
            AttendanceProperties properties,
            AuditService auditService
    ) {
        this.faceProvider = faceProvider;
        this.encryptionService = encryptionService;
        this.imageValidator = imageValidator;
        this.biometricRepository = biometricRepository;
        this.workerRepository = workerRepository;
        this.tokenRepository = tokenRepository;
        this.settingsRepository = settingsRepository;
        this.properties = properties;
        this.auditService = auditService;
    }

    @Transactional
    public VerificationOutcome verify(UUID actorWorkerId, VerificationMode mode, String targetWorkerCode, String imageBase64) {
        if (!faceProvider.isMock() && !properties.getBiometrics().isProductionEnabled()) {
            throw new AppException(
                    "PRODUCTION_BIOMETRICS_DISABLED",
                    "Production biometric verification is disabled until privacy and liveness approval.",
                    HttpStatus.SERVICE_UNAVAILABLE
            );
        }

        Worker actor = workerRepository.findById(actorWorkerId)
                .orElseThrow(() -> new ResourceNotFoundException("WORKER_NOT_FOUND", "Authenticated worker not found."));
        if (actor.getStatus() != WorkerStatus.REGISTERED) {
            throw new ForbiddenException("WORKER_NOT_ACTIVE", "Worker account is not active.");
        }

        AttendanceSettings settings = settingsRepository.findTopByOrderByEffectiveFromUtcDesc()
                .orElseThrow(() -> new IllegalStateException("Attendance settings not configured"));

        if (mode == VerificationMode.PROXY && !settings.isProxyEnabled()) {
            throw new ForbiddenException("PROXY_DISABLED", "Marking attendance for another worker is disabled by policy.");
        }

        byte[] imageBytes = imageValidator.decodeBase64(imageBase64);
        FaceRecognitionProvider.ExtractionResult probe;
        try {
            probe = faceProvider.extractEmbedding(imageBytes);
        } catch (AppException ex) {
            auditService.recordIndependent(
                    "BIOMETRIC_VERIFY_FAILED",
                    AuditActorType.WORKER,
                    actorWorkerId,
                    null,
                    Map.of("mode", mode.name(), "reasonCode", ex.getCode())
            );
            throw ex;
        }

        double threshold = settings.getFaceMatchThreshold();
        MatchCandidate match = mode == VerificationMode.SELF
                ? matchSpecificWorker(actor, probe.embedding(), threshold)
                : matchTargetWorkerForProxy(actor, targetWorkerCode, probe.embedding(), threshold);

        if (match == null) {
            auditService.recordIndependent(
                    "BIOMETRIC_VERIFY_FAILED",
                    AuditActorType.WORKER,
                    actorWorkerId,
                    mode == VerificationMode.SELF ? actorWorkerId : null,
                    Map.of("mode", mode.name(), "reasonCode", "FACE_NOT_RECOGNIZED")
            );
            throw new AppException(
                    "FACE_NOT_RECOGNIZED",
                    "We couldn't verify your face. Please try again in better lighting.",
                    HttpStatus.UNPROCESSABLE_ENTITY
            );
        }

        String rawToken = TokenHashUtil.generateOpaqueToken();
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(properties.getSecurity().getVerificationTokenTtlSeconds());
        BiometricVerificationToken tokenEntity = new BiometricVerificationToken(
                UUID.randomUUID(),
                TokenHashUtil.sha256Hex(rawToken),
                actorWorkerId,
                match.worker().getId(),
                mode.name(),
                match.similarity(),
                faceProvider.isMock(),
                expiresAt,
                now
        );
        tokenRepository.save(tokenEntity);

        auditService.record(
                "BIOMETRIC_VERIFY_SUCCEEDED",
                AuditActorType.WORKER,
                actorWorkerId,
                match.worker().getId(),
                Map.of("mode", mode.name(), "mockMode", faceProvider.isMock())
        );

        return new VerificationOutcome(
                rawToken,
                expiresAt,
                mode,
                match.worker().getId(),
                match.worker().getWorkerCode(),
                match.worker().getFullName(),
                faceProvider.isMock()
        );
    }

    private MatchCandidate matchSpecificWorker(Worker target, float[] probeEmbedding, double threshold) {
        WorkerBiometric bio = biometricRepository.findByWorkerId(target.getId())
                .orElseThrow(() -> new ValidationException("BIOMETRICS_NOT_ENROLLED", "Face template is not enrolled for this worker."));
        float[] reference = encryptionService.decrypt(bio.getEncryptedEmbedding(), bio.getEncryptionIv());
        double sim = faceProvider.cosineSimilarity(probeEmbedding, reference);
        return sim >= threshold ? new MatchCandidate(target, sim) : null;
    }

    private MatchCandidate matchTargetWorkerForProxy(Worker actor, String targetWorkerCode, float[] probeEmbedding, double threshold) {
        if (targetWorkerCode != null && !targetWorkerCode.isBlank()) {
            Worker requested = workerRepository.findByWorkerCode(targetWorkerCode.trim().toUpperCase())
                    .orElseThrow(() -> new ResourceNotFoundException("TARGET_WORKER_NOT_FOUND", "Target worker code was not found."));
            if (requested.getId().equals(actor.getId())) {
                throw new ValidationException("SELF_PROXY_NOT_ALLOWED", "Use regular self check-in for your own attendance.");
            }
            if (requested.getStatus() != WorkerStatus.REGISTERED) {
                throw new ValidationException("TARGET_WORKER_NOT_ACTIVE", "Target worker is not active for attendance.");
            }
            return matchSpecificWorker(requested, probeEmbedding, threshold);
        }

        // Identify matching registered worker excluding actor
        List<Worker> registered = workerRepository.findByStatusOrderByWorkerCodeAsc(WorkerStatus.REGISTERED);
        MatchCandidate best = null;
        for (Worker candidate : registered) {
            if (candidate.getId().equals(actor.getId())) {
                continue;
            }
            var bioOpt = biometricRepository.findByWorkerId(candidate.getId());
            if (bioOpt.isEmpty()) {
                continue;
            }
            WorkerBiometric bio = bioOpt.get();
            float[] ref = encryptionService.decrypt(bio.getEncryptedEmbedding(), bio.getEncryptionIv());
            double sim = faceProvider.cosineSimilarity(probeEmbedding, ref);
            if (sim >= threshold && (best == null || sim > best.similarity())) {
                best = new MatchCandidate(candidate, sim);
            }
        }
        return best;
    }

    public enum VerificationMode {
        SELF,
        PROXY
    }

    private record MatchCandidate(Worker worker, double similarity) {
    }

    public record VerificationOutcome(
            String verificationToken,
            Instant expiresAtUtc,
            VerificationMode mode,
            UUID matchedWorkerId,
            String matchedWorkerCode,
            String matchedWorkerName,
            boolean mockMode
    ) {
    }
}
