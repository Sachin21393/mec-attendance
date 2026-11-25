# Technical & Architectural Decisions

## ADR-001: Modular Monolith with Spring Boot 3.x and Java 21

- **Context:** Empty greenfield repository; local machine has Java 21.0.10 LTS, Docker 29.1.3, and Node 24.16.0.
- **Decision:** Build a single Spring Boot 3.x modular monolith (`com.example.attendance.*`) serving REST APIs under `/api/v1` and static vanilla HTML/CSS/ES-Module assets for both Worker PWA and Admin UI.
- **Rationale:** Minimizes deployment complexity, avoids distributed transaction issues for daily uniqueness and proxy-cap enforcement, and eliminates frontend build-tool fragility.

## ADR-002: Maven with Maven Wrapper (`mvnw` / `mvnw.cmd`)

- **Context:** Neither `mvn` nor `gradle` is installed on the host `PATH`.
- **Decision:** Use Maven with the checked-in Maven Wrapper (`mvnw`, `mvnw.cmd`) starting in PR 1.
- **Rationale:** Ensures every developer and CI runner builds with the exact same Maven version without global installation.

## ADR-003: PostgreSQL 16 + Flyway

- **Context:** Strong transactional guarantees (`UNIQUE (subject_worker_id, attendance_date)`, pessimistic/serializable concurrency control for proxy limits) and versioned migrations are required.
- **Decision:** Use PostgreSQL 16 as the primary relational store managed exclusively through Flyway SQL migrations (`db/migration/`). Local development runs PostgreSQL via Docker Compose; automated tests use Testcontainers (or H2 only where explicitly noted, preferring PostgreSQL parity via Testcontainers).

## ADR-004: Replaceable `FaceRecognitionProvider` & Selected Face Models

- **Decision:** Define a strict `FaceRecognitionProvider` SPI in `biometrics/` with two implementations:
  1. **`MockFaceRecognitionProvider` (`attendance.biometrics.provider=mock`)** — Default for local development, seed demos, and automated unit/UI tests. Uses deterministic synthetic embeddings/rules, displays a clear `[MOCK BIOMETRICS]` indicator in UI and logs, and never claims real recognition.
  2. **`OnnxFaceRecognitionProvider` (`attendance.biometrics.provider=onnx`)** — Server-side in-process inference via **ONNX Runtime for Java** (`com.microsoft.onnxruntime:onnxruntime`, MIT License).

- **Selected Models (ONNX Provider):**
  - **Face Detection & 5-Point Alignment:** OpenCV Zoo **YuNet** (`face_detection_yunet_2023mar.onnx`), **MIT License**.
  - **Face Embedding Extraction:** OpenCV Zoo **SFace** (`face_recognition_sface_2021dec.onnx`), **Apache License 2.0**.
  - **Embedding Dimension:** **128 dimensions** (`float[128]`, L2-normalized), configured explicitly (`attendance.biometrics.embedding-dimension=128`) and stored in `worker_biometrics.embedding_dimension`.
  - **Similarity Metric & Threshold:** **Cosine similarity**. OpenCV Zoo's documented baseline threshold for SFace is `0.363`, which serves only as an initial reference; the active threshold is externalized in configuration/settings and **must be calibrated on a consented representative evaluation set** before production use.
  - **Inference Location:** **Server-side in-process inside the Spring Boot JVM** using transient in-memory byte arrays. Moving inference off the browser prevents client-side tampering and ensures biometric templates never leave the server.

- **Known Liveness & Anti-Spoofing Limitations (Critical):**
  - YuNet + SFace perform **detection and recognition only** — they do **not** provide presentation attack detection (PAD / liveness).
  - Basic software quality checks (single-face enforcement, minimum face pixel width, detection confidence, brightness/sharpness heuristics) help reject poor captures but **do not prevent spoofing** via printed photographs, high-resolution phone/tablet screen replays, or masks.
  - **Production Gate:** Real worker biometric processing (`ATTENDANCE_PRODUCTION_BIOMETRICS_ENABLED=true`) MUST remain disabled until:
    1. A dedicated anti-spoofing/liveness module (or an approved active/passive PAD solution) is integrated and evaluated, OR the organization formally accepts the residual risk with compensating controls; and
    2. Legal/privacy review has approved the notice, consent, retention, and non-biometric manual exception workflow.
  - The UI and documentation will **never** claim spoof-proof attendance.

## ADR-005: Biometric Template Encryption at Rest

- **Decision:** Encrypt serialized `float[]` embeddings using **AES-256-GCM** with per-record random 96-bit IVs and a versioned key identifier (`key_version`). The master encryption key is injected via environment variable (`BIOMETRICS_ENCRYPTION_KEY_B64`) or a secret manager — never committed to source control.

## ADR-006: Authentication & Session Separation

- **Admin Auth:** Username/password (BCrypt/Argon2id) + server-side session/HttpOnly cookie (`ADMIN_SESSION`), role checks (`ADMIN`, `TECH_ADMIN`), CSRF token protection (`CookieCsrfTokenRepository`), and login rate limiting.
- **Worker Auth:** One-time admin invitation code + biometric enrollment -> server-issued random opaque token stored as SHA-256 hash in `worker_sessions` and delivered via `HttpOnly`, `SameSite=Lax` cookie (`WORKER_SESSION`). Client-side `localStorage` is never trusted for identity. If the cookie is cleared or revoked, the worker must re-verify securely.

## ADR-007: Attendance Day, Timezone, Overnight Shifts, and Proxy Cap

- **Source of Truth:** Server UTC clock (`Instant.now()`) converted to the configured workplace `ZoneId` (e.g., `Asia/Kolkata`).
- **Status Calculation:**
  - `ON_TIME`: `check_in_local <= shift_start + grace_period`
  - `LATE`: `check_in_local > shift_start + grace_period` (within the attendance day)
  - `ABSENT`: No valid check-in once the configured `absence_cutoff_local_time` has passed on a `WORKING` day
  - `HOLIDAY` / `WEEKLY_OFF`: Configured non-working days (never counted as absent)
  - `NOT_DUE`: Future working days or current working day before `absence_cutoff_local_time`
- **Overnight Shift Support:** If `absence_cutoff_local_time` or shift end wraps past midnight (`cutoff < shift_start`), the attendance day is anchored to the **shift start calendar date** in the workplace timezone.
- **Proxy Attendance Cap:** Default limit is **1 successful proxy check-in per `actor_worker_id` per `attendance_date`**, configurable by admin (`attendance_settings.max_proxy_per_actor_per_day`). Enforced inside a database transaction with row/advisory locking to prevent concurrent bypass. Failed proxy verification attempts are logged in `audit_events` for review and do not consume the successful proxy quota. Self-proxy is explicitly rejected.

## ADR-008: CSV Export & Formula-Injection Protection

- **Decision:** Generate CSV server-side as UTF-8 (with optional BOM or clean UTF-8 documented for Excel compatibility), RFC 4180 quoting, and prefix any cell value starting with `=`, `+`, `-`, `@`, `\t`, or `\r` with a leading single quote (`'`) to prevent spreadsheet formula injection. Dashboard summary counts and CSV totals use the same underlying aggregation service so figures always reconcile.
