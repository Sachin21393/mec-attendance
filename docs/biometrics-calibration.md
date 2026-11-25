# Biometrics Model Setup, Calibration & Liveness Limitations

## 1. Selected Models (ONNX Provider)

When running with `ATTENDANCE_BIOMETRICS_PROVIDER=onnx`:

- **Face Detection & 5-Landmark Alignment:** OpenCV Zoo **YuNet** (`face_detection_yunet_2023mar.onnx`), **MIT License**.
- **Face Embedding Extraction:** OpenCV Zoo **SFace** (`face_recognition_sface_2021dec.onnx`), **Apache License 2.0**.
- **Runtime:** Server-side in-process JVM inference via **ONNX Runtime** (`com.microsoft.onnxruntime:onnxruntime`, MIT License).
- **Embedding Dimension:** **128 dimensions** (`float[128]`), L2-normalized.
- **Distance Metric:** **Cosine similarity** (`dot(a, b) / (||a|| * ||b||)`).

Place downloaded model files at:
- `models/face_detection_yunet_2023mar.onnx`
- `models/face_recognition_sface_2021dec.onnx`

(Both paths are configurable via `ATTENDANCE_BIOMETRICS_YUNET_MODEL_PATH` and `ATTENDANCE_BIOMETRICS_SFACE_MODEL_PATH`.)

---

## 2. Threshold Calibration Process

The initial reference value `0.363` is only OpenCV Zoo's published baseline for SFace cosine similarity—it is **not** a universal threshold across lighting conditions, smartphone cameras, or user populations.

Before enabling `ATTENDANCE_PRODUCTION_BIOMETRICS_ENABLED=true`:
1. Assemble a **consented** representative evaluation dataset across target workplace lighting (indoor fluorescent, shaded outdoor, bright daylight) and representative Android/iOS front cameras.
2. Measure **False Accept Rate (FAR)** (impostor pairs) and **False Reject Rate (FRR)** (genuine pairs) across candidate thresholds (`0.30` to `0.55`).
3. Select the operating threshold via Admin Technical Settings (`faceMatchThreshold`) and document the measured FAR/FRR results.

---

## 3. Known Liveness & Anti-Spoofing Limitations

- `OnnxFaceRecognitionProvider` enforces image integrity (`ImageIO` decode, `160x160`–`4096x4096`), luminance bounds, single-face detection, minimum bounding box size (`80px`), and frontal eye-nose symmetry.
- **These passive checks do NOT constitute Presentation Attack Detection (PAD).** They cannot reliably defeat printed photos, high-resolution mobile screen replays, or masks.
- Do **not** claim spoof-proof attendance. Keep `ATTENDANCE_PRODUCTION_BIOMETRICS_ENABLED=false` until an approved PAD/liveness solution and legal/privacy review are signed off, and always keep the audited manual exception route available.
