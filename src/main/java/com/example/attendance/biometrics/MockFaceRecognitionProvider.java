package com.example.attendance.biometrics;

import com.example.attendance.common.AttendanceProperties;
import com.example.attendance.common.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Deterministic mock face provider for local development and automated testing.
 * Never represents real biometric recognition; clearly flagged via {@link #isMock()}.
 */
@Component
@ConditionalOnProperty(name = "attendance.biometrics.provider", havingValue = "mock", matchIfMissing = true)
public class MockFaceRecognitionProvider implements FaceRecognitionProvider {

    private static final Logger log = LoggerFactory.getLogger(MockFaceRecognitionProvider.class);

    private final int dimension;
    private final ImagePayloadValidator imageValidator;

    public MockFaceRecognitionProvider(AttendanceProperties properties, ImagePayloadValidator imageValidator) {
        this.dimension = properties.getBiometrics().getEmbeddingDimension();
        this.imageValidator = imageValidator;
        log.warn("Initialized MockFaceRecognitionProvider ({}D). This mode is for development/testing only and does NOT perform real facial recognition.", dimension);
    }

    @Override
    public ExtractionResult extractEmbedding(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length < 8) {
            throw new ValidationException("BIOMETRIC_IMAGE_INVALID", "Face capture payload is empty or too small.");
        }

        if (imageValidator.isSyntheticMockTokenPayload(imageBytes)) {
            String marker = new String(imageBytes, StandardCharsets.UTF_8);
            if (marker.contains("NO_FACE")) {
                throw new ValidationException("NO_FACE_DETECTED", "No face detected. Place your face inside the frame.");
            }
            if (marker.contains("MULTI_FACE")) {
                throw new ValidationException("MULTIPLE_FACES_DETECTED", "Multiple faces detected. Ensure only one person is in the frame.");
            }
            if (marker.contains("LOW_LIGHT")) {
                throw new ValidationException("POOR_LIGHTING", "Lighting is too low. Move to a brighter area and try again.");
            }
            if (marker.contains("LIVENESS_FAIL")) {
                throw new ValidationException("LIVENESS_CHECK_FAILED", "Liveness check failed. Look directly at the camera and try again.");
            }
        } else {
            // Real image bytes submitted in mock mode still undergo full ImageIO validation
            imageValidator.decodeAndValidateImage(imageBytes);
        }

        float[] embedding = deterministicUnitVector(imageBytes);
        return new ExtractionResult(embedding, 1, 0.99, true, "mock-liveness-not-verified");
    }

    @Override
    public double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            throw new IllegalArgumentException("Embedding vectors must have identical dimensions");
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    @Override
    public String providerName() {
        return "mock";
    }

    @Override
    public String modelName() {
        return "mock-deterministic-sface-placeholder";
    }

    @Override
    public String modelVersion() {
        return "mock-v1";
    }

    @Override
    public int embeddingDimension() {
        return dimension;
    }

    @Override
    public boolean isMock() {
        return true;
    }

    private float[] deterministicUnitVector(byte[] input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] block = md.digest(input);
            float[] vec = new float[dimension];
            double sumSq = 0.0;
            for (int i = 0; i < dimension; i++) {
                if (i > 0 && i % block.length == 0) {
                    block = md.digest(block);
                }
                byte b = block[i % block.length];
                float val = ((b & 0xFF) - 127.5f) / 127.5f;
                vec[i] = val;
                sumSq += val * val;
            }
            double norm = Math.sqrt(sumSq);
            if (norm > 0) {
                for (int i = 0; i < dimension; i++) {
                    vec[i] = (float) (vec[i] / norm);
                }
            }
            return vec;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
