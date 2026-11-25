package com.example.attendance.biometrics;

import com.example.attendance.common.AttendanceProperties;
import com.example.attendance.common.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import jakarta.annotation.PreDestroy;

/**
 * Production-candidate server-side face recognition provider using OpenCV Zoo models via ONNX Runtime:
 * <ul>
 *   <li>Detector: YuNet (MIT License)</li>
 *   <li>Recognizer: SFace (Apache License 2.0, 128-dimensional L2-normalized embedding)</li>
 * </ul>
 *
 * <p><b>Known Liveness Limitation:</b> YuNet + SFace perform face detection and recognition only.
 * Quality checks (single face, bounding-box size, pose symmetry, luminance) do NOT prevent
 * printed-photo, screen-replay, or mask presentation attacks. Keep
 * {@code attendance.biometrics.production-enabled=false} until an approved PAD/liveness layer
 * and privacy/legal review are completed.
 */
@Component
@ConditionalOnProperty(name = "attendance.biometrics.provider", havingValue = "onnx")
public class OnnxFaceRecognitionProvider implements FaceRecognitionProvider {

    private static final Logger log = LoggerFactory.getLogger(OnnxFaceRecognitionProvider.class);

    private static final int YUNET_INPUT_SIZE = 640;
    private static final int SFACE_INPUT_SIZE = 112;
    private static final float YUNET_SCORE_THRESHOLD = 0.60f;
    private static final float YUNET_NMS_THRESHOLD = 0.30f;
    private static final int MIN_FACE_PIXELS = 80;
    private static final int[] STRIDES = {8, 16, 32};

    private final ImagePayloadValidator imageValidator;
    private final int expectedDimension;
    private final OrtEnvironment env;
    private final OrtSession yunetSession;
    private final OrtSession sfaceSession;

    public OnnxFaceRecognitionProvider(AttendanceProperties properties, ImagePayloadValidator imageValidator) {
        this.imageValidator = imageValidator;
        this.expectedDimension = properties.getBiometrics().getEmbeddingDimension();
        if (this.expectedDimension != 128) {
            throw new IllegalStateException("SFace outputs 128-dimensional embeddings; configured embedding-dimension=" + this.expectedDimension);
        }

        Path yunetPath = Path.of(properties.getBiometrics().getYunetModelPath());
        Path sfacePath = Path.of(properties.getBiometrics().getSfaceModelPath());
        if (!Files.isRegularFile(yunetPath) || !Files.isRegularFile(sfacePath)) {
            throw new IllegalStateException(
                    "ONNX models not found. Expected YuNet at " + yunetPath + " and SFace at " + sfacePath
                    + ". See docs/biometrics-calibration.md for setup.");
        }

        try {
            this.env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            this.yunetSession = env.createSession(yunetPath.toString(), opts);
            this.sfaceSession = env.createSession(sfacePath.toString(), opts);
            log.info("Initialized OnnxFaceRecognitionProvider (YuNet + SFace 128D). Note: passive quality checks only; no dedicated PAD liveness model active.");
        } catch (OrtException e) {
            throw new IllegalStateException("Failed to initialize ONNX Runtime sessions", e);
        }
    }

    @PreDestroy
    public void close() {
        try {
            if (yunetSession != null) yunetSession.close();
            if (sfaceSession != null) sfaceSession.close();
        } catch (OrtException e) {
            log.warn("Error closing ONNX sessions", e);
        }
    }

    @Override
    public ExtractionResult extractEmbedding(byte[] imageBytes) {
        BufferedImage decoded = imageValidator.decodeAndValidateImage(imageBytes);
        BufferedImage resized = resize(decoded, YUNET_INPUT_SIZE, YUNET_INPUT_SIZE);
        List<DetectedFace> faces = detectFaces(resized);

        if (faces.isEmpty()) {
            throw new ValidationException("NO_FACE_DETECTED", "No face detected. Place your face inside the frame.");
        }
        if (faces.size() > 1) {
            throw new ValidationException("MULTIPLE_FACES_DETECTED", "Multiple faces detected. Ensure only one person is in the frame.");
        }

        DetectedFace face = faces.get(0);
        if (face.width < MIN_FACE_PIXELS || face.height < MIN_FACE_PIXELS) {
            throw new ValidationException("FACE_TOO_SMALL", "Move closer to the camera so your face fills the frame.");
        }
        if (!passesFrontalPoseHeuristic(face)) {
            throw new ValidationException("POSE_NOT_FRONTAL", "Look straight at the camera without tilting your head.");
        }

        BufferedImage aligned = cropAndScaleFace(resized, face);
        float[] embedding = runSface(aligned);
        return new ExtractionResult(embedding, 1, face.score, true, "passive-quality-only-no-pad");
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
        return (normA == 0.0 || normB == 0.0) ? 0.0 : dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    @Override
    public String providerName() {
        return "onnx";
    }

    @Override
    public String modelName() {
        return "opencv-zoo-yunet+sface";
    }

    @Override
    public String modelVersion() {
        return "yunet-2023mar+sface-2021dec";
    }

    @Override
    public int embeddingDimension() {
        return expectedDimension;
    }

    @Override
    public boolean isMock() {
        return false;
    }

    private List<DetectedFace> detectFaces(BufferedImage img640) {
        float[] tensorData = toBgrChwFloat(img640, false);
        long[] shape = {1, 3, YUNET_INPUT_SIZE, YUNET_INPUT_SIZE};
        String inputName = yunetSession.getInputNames().iterator().next();

        try (OnnxTensor input = OnnxTensor.createTensor(env, FloatBuffer.wrap(tensorData), shape);
             OrtSession.Result result = yunetSession.run(Map.of(inputName, input))) {
            List<DetectedFace> candidates = new ArrayList<>();
            for (int sIdx = 0; sIdx < STRIDES.length; sIdx++) {
                int stride = STRIDES[sIdx];
                int grid = YUNET_INPUT_SIZE / stride;
                float[][] cls = (float[][]) result.get(sIdx).getValue();
                float[][] obj = (float[][]) result.get(sIdx + 3).getValue();
                float[][] bbox = (float[][]) result.get(sIdx + 6).getValue();
                float[][] kps = (float[][]) result.get(sIdx + 9).getValue();

                for (int r = 0; r < grid; r++) {
                    for (int c = 0; c < grid; c++) {
                        int idx = r * grid + c;
                        float clsScore = Math.clamp(cls[idx][0], 0f, 1f);
                        float objScore = Math.clamp(obj[idx][0], 0f, 1f);
                        float score = (float) Math.sqrt(clsScore * objScore);
                        if (score < YUNET_SCORE_THRESHOLD) {
                            continue;
                        }
                        float cx = (c + bbox[idx][0]) * stride;
                        float cy = (r + bbox[idx][1]) * stride;
                        float w = (float) Math.exp(bbox[idx][2]) * stride;
                        float h = (float) Math.exp(bbox[idx][3]) * stride;
                        float x = cx - w / 2f;
                        float y = cy - h / 2f;

                        float rightEyeX = (c + kps[idx][0]) * stride;
                        float rightEyeY = (r + kps[idx][1]) * stride;
                        float leftEyeX = (c + kps[idx][2]) * stride;
                        float leftEyeY = (r + kps[idx][3]) * stride;
                        float noseX = (c + kps[idx][4]) * stride;
                        float noseY = (r + kps[idx][5]) * stride;
                        candidates.add(new DetectedFace(x, y, w, h, score, rightEyeX, rightEyeY, leftEyeX, leftEyeY, noseX, noseY));
                    }
                }
            }
            return nms(candidates, YUNET_NMS_THRESHOLD);
        } catch (OrtException e) {
            throw new IllegalStateException("YuNet face detection inference failed", e);
        }
    }

    private float[] runSface(BufferedImage face112) {
        float[] tensorData = toBgrChwFloat(face112, true);
        long[] shape = {1, 3, SFACE_INPUT_SIZE, SFACE_INPUT_SIZE};
        String inputName = sfaceSession.getInputNames().iterator().next();

        try (OnnxTensor input = OnnxTensor.createTensor(env, FloatBuffer.wrap(tensorData), shape);
             OrtSession.Result result = sfaceSession.run(Map.of(inputName, input))) {
            float[][] raw = (float[][]) result.get(0).getValue();
            float[] vec = raw[0];
            double norm = 0.0;
            for (float v : vec) {
                norm += v * v;
            }
            norm = Math.sqrt(norm);
            if (norm > 0) {
                for (int i = 0; i < vec.length; i++) {
                    vec[i] = (float) (vec[i] / norm);
                }
            }
            return vec;
        } catch (OrtException e) {
            throw new IllegalStateException("SFace embedding inference failed", e);
        }
    }

    private static boolean passesFrontalPoseHeuristic(DetectedFace face) {
        float eyeDx = Math.abs(face.leftEyeX - face.rightEyeX);
        if (eyeDx < 10f) {
            return false;
        }
        float noseOffsetLeft = Math.abs(face.leftEyeX - face.noseX);
        float noseOffsetRight = Math.abs(face.noseX - face.rightEyeX);
        float ratio = Math.min(noseOffsetLeft, noseOffsetRight) / Math.max(noseOffsetLeft, noseOffsetRight);
        return ratio >= 0.30f;
    }

    private static BufferedImage cropAndScaleFace(BufferedImage img, DetectedFace face) {
        int padX = Math.round(face.width * 0.15f);
        int padY = Math.round(face.height * 0.15f);
        int x = Math.max(0, Math.round(face.x) - padX);
        int y = Math.max(0, Math.round(face.y) - padY);
        int w = Math.min(img.getWidth() - x, Math.round(face.width) + padX * 2);
        int h = Math.min(img.getHeight() - y, Math.round(face.height) + padY * 2);
        BufferedImage crop = img.getSubimage(x, y, Math.max(1, w), Math.max(1, h));
        return resize(crop, SFACE_INPUT_SIZE, SFACE_INPUT_SIZE);
    }

    private static BufferedImage resize(BufferedImage src, int targetW, int targetH) {
        BufferedImage out = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, targetW, targetH, null);
        g.dispose();
        return out;
    }

    private static float[] toBgrChwFloat(BufferedImage img, boolean normalizeMinusOneToOne) {
        int w = img.getWidth();
        int h = img.getHeight();
        int hw = w * h;
        float[] out = new float[3 * hw];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y);
                float r = (rgb >> 16) & 0xFF;
                float g = (rgb >> 8) & 0xFF;
                float b = rgb & 0xFF;
                if (normalizeMinusOneToOne) {
                    b = (b - 127.5f) / 128.0f;
                    g = (g - 127.5f) / 128.0f;
                    r = (r - 127.5f) / 128.0f;
                }
                int idx = y * w + x;
                out[idx] = b;
                out[hw + idx] = g;
                out[2 * hw + idx] = r;
            }
        }
        return out;
    }

    private static List<DetectedFace> nms(List<DetectedFace> boxes, float iouThreshold) {
        boxes.sort(Comparator.comparingDouble((DetectedFace f) -> f.score).reversed());
        List<DetectedFace> kept = new ArrayList<>();
        for (DetectedFace candidate : boxes) {
            boolean suppressed = false;
            for (DetectedFace existing : kept) {
                if (iou(candidate, existing) > iouThreshold) {
                    suppressed = true;
                    break;
                }
            }
            if (!suppressed) {
                kept.add(candidate);
            }
        }
        return kept;
    }

    private static float iou(DetectedFace a, DetectedFace b) {
        float x1 = Math.max(a.x, b.x);
        float y1 = Math.max(a.y, b.y);
        float x2 = Math.min(a.x + a.width, b.x + b.width);
        float y2 = Math.min(a.y + a.height, b.y + b.height);
        float inter = Math.max(0f, x2 - x1) * Math.max(0f, y2 - y1);
        float union = a.width * a.height + b.width * b.height - inter;
        return union <= 0f ? 0f : inter / union;
    }

    private record DetectedFace(
            float x, float y, float width, float height, float score,
            float rightEyeX, float rightEyeY, float leftEyeX, float leftEyeY, float noseX, float noseY
    ) {
    }
}
