package com.example.attendance.biometrics;

public interface FaceRecognitionProvider {

    ExtractionResult extractEmbedding(byte[] imageBytes);

    double cosineSimilarity(float[] a, float[] b);

    String providerName();

    String modelName();

    String modelVersion();

    int embeddingDimension();

    boolean isMock();

    record ExtractionResult(
            float[] embedding,
            int faceCount,
            double detectionConfidence,
            boolean livenessPassed,
            String livenessNote
    ) {
    }
}
