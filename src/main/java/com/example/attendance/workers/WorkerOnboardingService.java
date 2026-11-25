package com.example.attendance.workers;

import com.example.attendance.audit.AuditActorType;
import com.example.attendance.audit.AuditService;
import com.example.attendance.auth.TokenHashUtil;
import com.example.attendance.biometrics.BiometricEncryptionService;
import com.example.attendance.biometrics.BiometricsConfigController;
import com.example.attendance.biometrics.FaceRecognitionProvider;
import com.example.attendance.biometrics.ImagePayloadValidator;
import com.example.attendance.biometrics.WorkerBiometric;
import com.example.attendance.biometrics.WorkerBiometricRepository;
import com.example.attendance.common.AppException;
import com.example.attendance.common.AttendanceProperties;
import com.example.attendance.common.ConflictException;
import com.example.attendance.common.ResourceNotFoundException;
import com.example.attendance.common.ValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class WorkerOnboardingService {

    private final WorkerRepository workerRepository;
    private final WorkerInvitationRepository invitationRepository;
    private final ConsentRecordRepository consentRecordRepository;
    private final WorkerBiometricRepository biometricRepository;
    private final WorkerSessionRepository sessionRepository;
    private final FaceRecognitionProvider faceProvider;
    private final BiometricEncryptionService encryptionService;
    private final ImagePayloadValidator imageValidator;
    private final AttendanceProperties properties;
    private final AuditService auditService;

    public WorkerOnboardingService(
            WorkerRepository workerRepository,
            WorkerInvitationRepository invitationRepository,
            ConsentRecordRepository consentRecordRepository,
            WorkerBiometricRepository biometricRepository,
            WorkerSessionRepository sessionRepository,
            FaceRecognitionProvider faceProvider,
            BiometricEncryptionService encryptionService,
            ImagePayloadValidator imageValidator,
            AttendanceProperties properties,
            AuditService auditService
    ) {
        this.workerRepository = workerRepository;
        this.invitationRepository = invitationRepository;
        this.consentRecordRepository = consentRecordRepository;
        this.biometricRepository = biometricRepository;
        this.sessionRepository = sessionRepository;
        this.faceProvider = faceProvider;
        this.encryptionService = encryptionService;
        this.imageValidator = imageValidator;
        this.properties = properties;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public InvitationValidationResult validateInvitation(String rawCode) {
        String normalized = normalizeCode(rawCode);
        String codeHash = TokenHashUtil.sha256Hex(normalized);
        WorkerInvitation invitation = invitationRepository.findByCodeHash(codeHash)
                .orElseThrow(() -> new ResourceNotFoundException("INVITATION_NOT_FOUND", "Invitation code is invalid."));

        Instant now = Instant.now();
        if (invitation.getConsumedAtUtc() != null) {
            throw new ConflictException("INVITATION_ALREADY_USED", "This invitation code has already been used.");
        }
        if (!invitation.getExpiresAtUtc().isAfter(now)) {
            throw new ValidationException("INVITATION_EXPIRED", "This invitation code has expired. Ask your admin for a new one.");
        }

        Worker worker = workerRepository.findById(invitation.getWorkerId())
                .orElseThrow(() -> new ResourceNotFoundException("WORKER_NOT_FOUND", "Invited worker record not found."));

        if (worker.getStatus() == WorkerStatus.DISABLED) {
            throw new ConflictException("WORKER_DISABLED", "This worker account is disabled.");
        }
        if (worker.getStatus() == WorkerStatus.REGISTERED) {
            throw new ConflictException("WORKER_ALREADY_REGISTERED", "This worker is already registered.");
        }

        return new InvitationValidationResult(
                worker.getWorkerCode(),
                worker.getFullName(),
                worker.getTeamName(),
                invitation.getExpiresAtUtc(),
                BiometricsConfigController.CURRENT_CONSENT_VERSION,
                BiometricsConfigController.CONSENT_NOTICE_SUMMARY
        );
    }

    @Transactional
    public RegistrationOutcome register(
            String rawCode,
            String fullName,
            boolean consentGiven,
            String consentVersion,
            String imageBase64,
            String deviceLabel,
            String remoteAddr
    ) {
        String normalizedCode = normalizeCode(rawCode);
        String codeHash = TokenHashUtil.sha256Hex(normalizedCode);

        WorkerInvitation invitation = invitationRepository.findByCodeHash(codeHash)
                .orElseThrow(() -> new ResourceNotFoundException("INVITATION_NOT_FOUND", "Invitation code is invalid."));

        Instant now = Instant.now();
        if (invitation.getConsumedAtUtc() != null) {
            throw new ConflictException("INVITATION_ALREADY_USED", "This invitation code has already been used.");
        }
        if (!invitation.getExpiresAtUtc().isAfter(now)) {
            throw new ValidationException("INVITATION_EXPIRED", "This invitation code has expired.");
        }

        Worker worker = workerRepository.findById(invitation.getWorkerId())
                .orElseThrow(() -> new ResourceNotFoundException("WORKER_NOT_FOUND", "Invited worker not found."));

        if (worker.getStatus() != WorkerStatus.INVITED) {
            throw new ConflictException("WORKER_NOT_ELIGIBLE", "Worker cannot register in status " + worker.getStatus());
        }

        String effectiveConsentVersion = (consentVersion == null || consentVersion.isBlank())
                ? BiometricsConfigController.CURRENT_CONSENT_VERSION
                : consentVersion.trim();
        String ipHash = remoteAddr != null ? TokenHashUtil.sha256Hex(remoteAddr) : null;

        consentRecordRepository.save(new ConsentRecord(
                UUID.randomUUID(),
                worker.getId(),
                effectiveConsentVersion,
                consentGiven,
                BiometricsConfigController.CONSENT_NOTICE_SUMMARY,
                ipHash,
                now
        ));

        if (!consentGiven) {
            auditService.recordIndependent(
                    "WORKER_CONSENT_DECLINED",
                    AuditActorType.WORKER,
                    worker.getId(),
                    worker.getId(),
                    Map.of("consentVersion", effectiveConsentVersion)
            );
            throw new AppException(
                    "CONSENT_REQUIRED_FOR_BIOMETRICS",
                    "Face enrollment requires consent. You can use manual attendance through your supervisor or administrator.",
                    HttpStatus.UNPROCESSABLE_ENTITY
            );
        }

        if (!faceProvider.isMock() && !properties.getBiometrics().isProductionEnabled()) {
            throw new AppException(
                    "PRODUCTION_BIOMETRICS_DISABLED",
                    "Production biometric enrollment is currently disabled pending privacy and liveness approval.",
                    HttpStatus.SERVICE_UNAVAILABLE
            );
        }

        byte[] imageBytes = imageValidator.decodeBase64(imageBase64);
        FaceRecognitionProvider.ExtractionResult extraction = faceProvider.extractEmbedding(imageBytes);
        BiometricEncryptionService.EncryptedTemplate encrypted = encryptionService.encrypt(extraction.embedding());

        if (biometricRepository.findByWorkerId(worker.getId()).isPresent()) {
            throw new ConflictException("BIOMETRICS_ALREADY_ENROLLED", "Biometrics already exist for this worker.");
        }

        biometricRepository.save(new WorkerBiometric(
                UUID.randomUUID(),
                worker.getId(),
                encrypted.ciphertext(),
                encrypted.iv(),
                encrypted.keyVersion(),
                faceProvider.modelName(),
                faceProvider.modelVersion(),
                faceProvider.embeddingDimension(),
                effectiveConsentVersion,
                now
        ));

        worker.setFullName(fullName.trim());
        worker.setStatus(WorkerStatus.REGISTERED);
        worker.setUpdatedAt(now);
        workerRepository.save(worker);

        invitation.setConsumedAtUtc(now);
        invitationRepository.save(invitation);

        String rawSessionToken = TokenHashUtil.generateOpaqueToken();
        Duration ttl = Duration.ofDays(properties.getSecurity().getWorkerSessionTtlDays());
        WorkerSession session = new WorkerSession(
                UUID.randomUUID(),
                worker.getId(),
                TokenHashUtil.sha256Hex(rawSessionToken),
                sanitizeDeviceLabel(deviceLabel),
                now.plus(ttl),
                now
        );
        sessionRepository.save(session);

        auditService.record(
                "WORKER_REGISTERED",
                AuditActorType.WORKER,
                worker.getId(),
                worker.getId(),
                Map.of(
                        "workerCode", worker.getWorkerCode(),
                        "provider", faceProvider.providerName(),
                        "mockMode", faceProvider.isMock(),
                        "consentVersion", effectiveConsentVersion
                )
        );

        return new RegistrationOutcome(worker, rawSessionToken, ttl, faceProvider.isMock());
    }

    private static String normalizeCode(String rawCode) {
        if (rawCode == null || rawCode.isBlank()) {
            throw new ValidationException("INVITATION_CODE_REQUIRED", "Invitation code is required.");
        }
        return rawCode.trim().toUpperCase();
    }

    private static String sanitizeDeviceLabel(String deviceLabel) {
        if (deviceLabel == null || deviceLabel.isBlank()) {
            return "Mobile Browser";
        }
        String trimmed = deviceLabel.trim();
        return trimmed.length() > 160 ? trimmed.substring(0, 160) : trimmed;
    }

    public record InvitationValidationResult(
            String workerCode,
            String suggestedName,
            String teamName,
            Instant expiresAtUtc,
            String consentVersion,
            String consentNotice
    ) {
    }

    public record RegistrationOutcome(
            Worker worker,
            String rawSessionToken,
            Duration sessionTtl,
            boolean mockBiometrics
    ) {
    }
}
