package com.example.attendance.biometrics;

import com.example.attendance.common.AttendanceProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/biometrics")
public class BiometricsConfigController {

    public static final String CURRENT_CONSENT_VERSION = "2026-11-v1";
    public static final String CONSENT_NOTICE_SUMMARY =
            "Your face image is processed transiently to generate an encrypted mathematical attendance template. "
            + "Raw camera photos are discarded after enrollment/check-in. You may decline and use manual attendance, "
            + "or request correction or deletion through your administrator.";

    private final FaceRecognitionProvider faceProvider;
    private final AttendanceProperties properties;

    public BiometricsConfigController(FaceRecognitionProvider faceProvider, AttendanceProperties properties) {
        this.faceProvider = faceProvider;
        this.properties = properties;
    }

    @GetMapping("/config")
    public BiometricsConfigResponse config() {
        return new BiometricsConfigResponse(
                faceProvider.providerName(),
                faceProvider.modelName(),
                faceProvider.modelVersion(),
                faceProvider.embeddingDimension(),
                faceProvider.isMock(),
                properties.getBiometrics().isProductionEnabled(),
                CURRENT_CONSENT_VERSION,
                CONSENT_NOTICE_SUMMARY,
                "Please ask your site supervisor or administrator to record manual attendance."
        );
    }

    public record BiometricsConfigResponse(
            String provider,
            String modelName,
            String modelVersion,
            int embeddingDimension,
            boolean mockMode,
            boolean productionBiometricsEnabled,
            String consentVersion,
            String consentNotice,
            String manualAttendanceGuidance
    ) {
    }
}
