package com.example.attendance.biometrics;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtLoggingLevel;
import ai.onnxruntime.OrtSession;
import com.example.attendance.common.AttendanceProperties;
import com.example.attendance.common.ValidationException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.image.BufferedImage;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Server-side face detection and recognition provider using OpenCV Zoo ONNX models:
 * <ul>
 *   <li>Detector: YuNet ({@code face_detection_yunet_2023mar.onnx}, MIT License)</li>
 *   <li>Recognizer: SFace ({@code face_recognition_sface_2021dec.onnx}, Apache License 2.0, 128-D L2-normalized)</li>
 * </ul>
 *
 * <p><b>Known Liveness Limitation:</b> YuNet + SFace perform face detection and recognition only.
 * Quality checks (single face, bounding-box size, pose symmetry, luminance) do NOT prevent
 * printed-photo, screen-replay, or mask presentation attacks. Production biometric enrollment
 * remains gated by {@code attendance.biometrics.production-enabled}.
 */
@Component
@ConditionalOnProperty(name = "attendance.biometrics.provider", havingValue = "onnx")
public class OnnxFaceRecognitionProvider implements FaceRecognitionProvider {

    private static final Logger log = LoggerFactory.getLogger(OnnxFaceRecognitionProvider.class);

    private static final int YUNET_INPUT_SIZE = 640;
    private static final int SFACE_INPUT_SIZE = 112;
    private static final int MAX_WORKING_IMAGE_SIDE = 960;
    private static final float YUNET_SCORE_THRESHOLD = 0.65f;
    private static final float YUNET_NMS_THRESHOLD = 0.30f;
    private static final float MIN_FACE_PIXELS = 48f;
    private static final int[] STRIDES = {8, 16, 32};

    // Standard ArcFace / SFace 112x112 reference 5-point landmarks:
    // [rightEye, leftEye, nose, rightMouthCorner, leftMouthCorner]
    private static final double[][] REF_LANDMARKS_112 = {
            {38.2946, 51.6963},
            {73.5318, 51.5014},
            {56.0252, 71.7366},
            {41.5493, 92.3655},
            {70.7299, 92.2041}
    };

    private final ImagePayloadValidator imageValidator;
    private final int expectedDimension;
    private final OrtEnvironment env;
    private final OrtSession yunetSession;
    private final OrtSession sfaceSession;
    private final ExecutorService onnxExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "onnx-inference");
        t.setDaemon(true);
        return t;
    });

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
                    "ONNX models not found. Expected YuNet at " + yunetPath.toAbsolutePath()
                    + " and SFace at " + sfacePath.toAbsolutePath()
                    + ". See docs/biometrics-calibration.md for setup.");
        }

        try (OrtSession.SessionOptions opts = new OrtSession.SessionOptions()) {
            // Keep ONNX native threads and arenas bounded for 512 MiB cloud containers
            opts.setIntraOpNumThreads(1);
            opts.setInterOpNumThreads(1);
            opts.setCPUArenaAllocator(false);
            opts.setMemoryPatternOptimization(false);
            opts.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR);
            this.env = OrtEnvironment.getEnvironment();
            this.yunetSession = env.createSession(yunetPath.toString(), opts);
            this.sfaceSession = env.createSession(sfacePath.toString(), opts);
            log.info("Initialized OnnxFaceRecognitionProvider (YuNet + SFace 128D RGB[0..255], single-thread bounded memory).");
        } catch (OrtException e) {
            throw new IllegalStateException("Failed to initialize ONNX Runtime sessions", e);
        }
    }

    @PreDestroy
    public void close() {
        onnxExecutor.shutdownNow();
        try {
            if (yunetSession != null) yunetSession.close();
            if (sfaceSession != null) sfaceSession.close();
        } catch (OrtException e) {
            log.warn("Error closing ONNX sessions", e);
        }
    }

    @Override
    public ExtractionResult extractEmbedding(byte[] imageBytes) {
        // Run decode + detection + recognition single-flight so parallel requests never stack image/native buffers
        return runOnSingleOnnxThread(() -> extractEmbeddingSerial(imageBytes));
    }

    private ExtractionResult extractEmbeddingSerial(byte[] imageBytes) {
        BufferedImage decoded = capWorkingResolution(imageValidator.decodeAndValidateImage(imageBytes));
        LetterboxImage lb = letterbox(decoded, YUNET_INPUT_SIZE);
        List<DetectedFace> faces = detectFaces(lb.image());

        if (faces.isEmpty()) {
            throw new ValidationException("NO_FACE_DETECTED", "No face detected. Place your face inside the frame.");
        }
        if (faces.size() > 1) {
            throw new ValidationException("MULTIPLE_FACES_DETECTED", "Multiple faces detected. Ensure only one person is in the frame.");
        }

        DetectedFace f = faces.get(0);
        // Map coordinates back from 640x640 letterbox space to original image space
        DetectedFace origFace = new DetectedFace(
                (f.x - lb.padX) / lb.scale,
                (f.y - lb.padY) / lb.scale,
                f.width / lb.scale,
                f.height / lb.scale,
                f.score,
                (f.rightEyeX - lb.padX) / lb.scale,
                (f.rightEyeY - lb.padY) / lb.scale,
                (f.leftEyeX - lb.padX) / lb.scale,
                (f.leftEyeY - lb.padY) / lb.scale,
                (f.noseX - lb.padX) / lb.scale,
                (f.noseY - lb.padY) / lb.scale,
                (f.rightMouthX - lb.padX) / lb.scale,
                (f.rightMouthY - lb.padY) / lb.scale,
                (f.leftMouthX - lb.padX) / lb.scale,
                (f.leftMouthY - lb.padY) / lb.scale
        );

        if (origFace.width < MIN_FACE_PIXELS || origFace.height < MIN_FACE_PIXELS) {
            throw new ValidationException("FACE_TOO_SMALL", "Move closer to the camera so your face fills the frame.");
        }
        if (!passesFrontalPoseHeuristic(origFace)) {
            throw new ValidationException("POSE_NOT_FRONTAL", "Look straight at the camera without tilting your head.");
        }

        BufferedImage aligned112 = alignFace112(decoded, origFace);
        float[] embedding = runSface(aligned112);
        return new ExtractionResult(embedding, 1, origFace.score, true, "passive-quality-only-no-pad");
    }

    private <T> T runOnSingleOnnxThread(Callable<T> task) {
        try {
            return onnxExecutor.submit(task).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during face recognition inference", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("Face recognition inference failed", e.getCause());
        }
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
        return "yunet-2023mar+sface-2021dec-rgb255";
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
        float[] tensorData = toChwFloat0To255(img640, false);
        long[] shape = {1, 3, YUNET_INPUT_SIZE, YUNET_INPUT_SIZE};

        try (OnnxTensor input = OnnxTensor.createTensor(env, FloatBuffer.wrap(tensorData), shape);
             OrtSession.Result result = yunetSession.run(Map.of("input", input))) {
            List<DetectedFace> candidates = new ArrayList<>();
            for (int stride : STRIDES) {
                int grid = YUNET_INPUT_SIZE / stride;
                float[][] cls = ((float[][][]) result.get("cls_" + stride).orElseThrow().getValue())[0];
                float[][] obj = ((float[][][]) result.get("obj_" + stride).orElseThrow().getValue())[0];
                float[][] bbox = ((float[][][]) result.get("bbox_" + stride).orElseThrow().getValue())[0];
                float[][] kps = ((float[][][]) result.get("kps_" + stride).orElseThrow().getValue())[0];

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

                        candidates.add(new DetectedFace(
                                x, y, w, h, score,
                                (c + kps[idx][0]) * stride, (r + kps[idx][1]) * stride,
                                (c + kps[idx][2]) * stride, (r + kps[idx][3]) * stride,
                                (c + kps[idx][4]) * stride, (r + kps[idx][5]) * stride,
                                (c + kps[idx][6]) * stride, (r + kps[idx][7]) * stride,
                                (c + kps[idx][8]) * stride, (r + kps[idx][9]) * stride
                        ));
                    }
                }
            }
            return nms(candidates, YUNET_NMS_THRESHOLD);
        } catch (OrtException e) {
            throw new IllegalStateException("YuNet face detection inference failed", e);
        }
    }

    private float[] runSface(BufferedImage face112) {
        // OpenCV FaceRecognizerSF::feature feeds 112x112 RGB [0..255]; the ONNX graph normalizes internally.
        float[] tensorData = toChwFloat0To255(face112, true);
        long[] shape = {1, 3, SFACE_INPUT_SIZE, SFACE_INPUT_SIZE};

        try (OnnxTensor input = OnnxTensor.createTensor(env, FloatBuffer.wrap(tensorData), shape);
             OrtSession.Result result = sfaceSession.run(Map.of("data", input))) {
            float[][] raw = (float[][]) result.get("fc1").orElseThrow().getValue();
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
        return ratio >= 0.25f;
    }

    /**
     * Estimates a 2D similarity transform (scale, rotation, translation) mapping the 5 detected
     * facial landmarks to the standard 112x112 SFace/ArcFace reference template.
     */
    private static BufferedImage alignFace112(BufferedImage src, DetectedFace face) {
        double[][] srcPts = {
                {face.rightEyeX, face.rightEyeY},
                {face.leftEyeX, face.leftEyeY},
                {face.noseX, face.noseY},
                {face.rightMouthX, face.rightMouthY},
                {face.leftMouthX, face.leftMouthY}
        };

        AffineTransform transform = estimateSimilarityTransform(srcPts, REF_LANDMARKS_112);
        if (transform == null) {
            return fallbackCropAndScale(src, face);
        }

        BufferedImage aligned = new BufferedImage(SFACE_INPUT_SIZE, SFACE_INPUT_SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = aligned.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, transform, null);
        g.dispose();
        return aligned;
    }

    private static AffineTransform estimateSimilarityTransform(double[][] src, double[][] dst) {
        int n = src.length;
        double meanSrcX = 0, meanSrcY = 0, meanDstX = 0, meanDstY = 0;
        for (int i = 0; i < n; i++) {
            meanSrcX += src[i][0];
            meanSrcY += src[i][1];
            meanDstX += dst[i][0];
            meanDstY += dst[i][1];
        }
        meanSrcX /= n;
        meanSrcY /= n;
        meanDstX /= n;
        meanDstY /= n;

        double varSrc = 0;
        double aNum = 0;
        double bNum = 0;
        for (int i = 0; i < n; i++) {
            double sx = src[i][0] - meanSrcX;
            double sy = src[i][1] - meanSrcY;
            double dx = dst[i][0] - meanDstX;
            double dy = dst[i][1] - meanDstY;
            varSrc += sx * sx + sy * sy;
            aNum += sx * dx + sy * dy;
            bNum += sx * dy - sy * dx;
        }
        if (varSrc < 1e-6) {
            return null;
        }
        double a = aNum / varSrc;
        double b = bNum / varSrc;
        double tx = meanDstX - (a * meanSrcX - b * meanSrcY);
        double ty = meanDstY - (b * meanSrcX + a * meanSrcY);
        AffineTransform at = new AffineTransform(a, b, -b, a, tx, ty);
        try {
            at.createInverse();
            return at;
        } catch (NoninvertibleTransformException e) {
            return null;
        }
    }

    private static BufferedImage fallbackCropAndScale(BufferedImage img, DetectedFace face) {
        int padX = Math.round(face.width * 0.15f);
        int padY = Math.round(face.height * 0.15f);
        int x = Math.max(0, Math.round(face.x) - padX);
        int y = Math.max(0, Math.round(face.y) - padY);
        int w = Math.min(img.getWidth() - x, Math.round(face.width) + padX * 2);
        int h = Math.min(img.getHeight() - y, Math.round(face.height) + padY * 2);
        BufferedImage crop = img.getSubimage(x, y, Math.max(1, w), Math.max(1, h));
        BufferedImage out = new BufferedImage(SFACE_INPUT_SIZE, SFACE_INPUT_SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(crop, 0, 0, SFACE_INPUT_SIZE, SFACE_INPUT_SIZE, null);
        g.dispose();
        return out;
    }

    private static BufferedImage capWorkingResolution(BufferedImage src) {
        int maxDim = Math.max(src.getWidth(), src.getHeight());
        if (maxDim <= MAX_WORKING_IMAGE_SIDE) {
            return src;
        }
        double scale = (double) MAX_WORKING_IMAGE_SIDE / maxDim;
        int w = Math.max(1, (int) Math.round(src.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(src.getHeight() * scale));
        BufferedImage resized = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return resized;
    }

    private static LetterboxImage letterbox(BufferedImage src, int targetSize) {
        double scale = Math.min((double) targetSize / src.getWidth(), (double) targetSize / src.getHeight());
        int newW = (int) Math.round(src.getWidth() * scale);
        int newH = (int) Math.round(src.getHeight() * scale);
        int padX = (targetSize - newW) / 2;
        int padY = (targetSize - newH) / 2;

        BufferedImage out = new BufferedImage(targetSize, targetSize, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, targetSize, targetSize);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, padX, padY, newW, newH, null);
        g.dispose();
        return new LetterboxImage(out, (float) scale, padX, padY);
    }

    private static float[] toChwFloat0To255(BufferedImage img, boolean rgbOrder) {
        int w = img.getWidth();
        int h = img.getHeight();
        int hw = w * h;
        int[] pixels = new int[hw];
        img.getRGB(0, 0, w, h, pixels, 0, w);
        float[] out = new float[3 * hw];
        for (int idx = 0; idx < hw; idx++) {
            int argb = pixels[idx];
            float r = (argb >> 16) & 0xFF;
            float g = (argb >> 8) & 0xFF;
            float b = argb & 0xFF;
            out[idx] = rgbOrder ? r : b;
            out[hw + idx] = g;
            out[2 * hw + idx] = rgbOrder ? b : r;
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

    private record LetterboxImage(BufferedImage image, float scale, int padX, int padY) {
    }

    private record DetectedFace(
            float x, float y, float width, float height, float score,
            float rightEyeX, float rightEyeY,
            float leftEyeX, float leftEyeY,
            float noseX, float noseY,
            float rightMouthX, float rightMouthY,
            float leftMouthX, float leftMouthY
    ) {
    }
}
