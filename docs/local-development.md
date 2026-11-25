# Local Development & Environment Configuration

> **Status (PR 0):** Repository discovery and technical plan only. The Spring Boot application, Maven Wrapper (`mvnw` / `mvnw.cmd`), and `docker-compose.yml` will be added in **PR 1** (and Docker Compose finalized in **PR 11**).

---

## 1. Prerequisites

| Tool | Required Version | Verified on Local Host |
|---|---|---|
| Java JDK | 21 (LTS) | `21.0.10` |
| Docker Engine / Desktop | 24+ (for PostgreSQL & Testcontainers) | `29.1.3` |
| Git | 2.x | `2.50.1` |
| Maven | Bundled via `./mvnw` / `.\mvnw.cmd` (added in PR 1) | Not in global `PATH` |
| Node.js (optional, frontend tests/lint) | 20+ | `v24.16.0` |

---

## 2. Planned Build & Run Commands (Effective from PR 1)

Once PR 1 establishes the Maven project and Docker Compose file:

```powershell
# 1. Start PostgreSQL 16 locally
docker compose up -d postgres

# 2. Build and run all automated tests
.\mvnw.cmd test

# 3. Package the executable JAR
.\mvnw.cmd clean package

# 4. Run the application locally (uses mock biometrics by default)
.\mvnw.cmd spring-boot:run
```

On Linux / macOS:

```bash
docker compose up -d postgres
./mvnw test
./mvnw spring-boot:run
```

---

## 3. Environment Variables & Secrets

Never commit real secrets, production keys, or `.env` files containing credentials to Git. For local development, copy `.env.example` (to be added in PR 1) to `.env` or export variables in your shell.

### Core Database & Server

| Variable | Required | Default (Local Dev) | Description |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | No | `local` | Active Spring profile (`local`, `test`, `prod`). |
| `SERVER_PORT` | No | `8080` | HTTP port exposed by Spring Boot. |
| `DB_URL` | Yes (prod) | `jdbc:postgresql://localhost:5432/attendance` | JDBC PostgreSQL connection URL. |
| `DB_USERNAME` | Yes (prod) | `attendance` | PostgreSQL username. |
| `DB_PASSWORD` | Yes | *(dev-only placeholder in local profile)* | PostgreSQL password. |

### Security, Sessions & Admin Bootstrap

| Variable | Required | Default (Local Dev) | Description |
|---|---|---|---|
| `BIOMETRICS_ENCRYPTION_KEY_B64` | Yes | *(ephemeral/dev-only key in `local` profile)* | Base64-encoded 256-bit AES-GCM key used to encrypt `worker_biometrics`. |
| `BIOMETRICS_KEY_VERSION` | No | `v1` | Key identifier stored with each encrypted template for rotation. |
| `APP_COOKIE_SECURE` | No | `false` (`local`), `true` (`prod`) | Sets `Secure` flag on session & CSRF cookies (must be `true` behind HTTPS). |
| `ADMIN_BOOTSTRAP_USERNAME` | No | `admin` (`local` only) | Initial admin username created only if no admin exists in `local`/seed mode. |
| `ADMIN_BOOTSTRAP_PASSWORD` | Yes (when bootstrapping) | *(env-only; never hardcode in prod)* | Initial admin password hashed with BCrypt/Argon2id on startup. |

### Face Recognition & Biometric Safety Gate

| Variable | Required | Default | Description |
|---|---|---|---|
| `ATTENDANCE_BIOMETRICS_PROVIDER` | No | `mock` | `mock` for deterministic dev/test mode; `onnx` for YuNet + SFace inference. |
| `ATTENDANCE_PRODUCTION_BIOMETRICS_ENABLED` | No | `false` | Safety gate preventing real biometric enrollment/matching until legal & PAD approval. |
| `ATTENDANCE_BIOMETRICS_YUNET_MODEL_PATH` | When `onnx` | `models/face_detection_yunet_2023mar.onnx` | Path to MIT-licensed YuNet ONNX detector model. |
| `ATTENDANCE_BIOMETRICS_SFACE_MODEL_PATH` | When `onnx` | `models/face_recognition_sface_2021dec.onnx` | Path to Apache-2.0 SFace ONNX embedding model. |
| `ATTENDANCE_BIOMETRICS_EMBEDDING_DIM` | No | `128` | Expected embedding dimension (`128` for SFace). |
| `ATTENDANCE_BIOMETRICS_MATCH_THRESHOLD` | No | `0.363` | Cosine similarity threshold (reference baseline; must be calibrated before production). |
| `ATTENDANCE_BIOMETRICS_MAX_IMAGE_BYTES` | No | `5242880` (5 MB) | Max accepted camera upload payload size. |

### Workplace Defaults (Overridable in Admin Settings UI)

| Variable | Required | Default | Description |
|---|---|---|---|
| `ATTENDANCE_DEFAULT_TIMEZONE` | No | `Asia/Kolkata` | Initial workplace IANA timezone used when seeding `attendance_settings`. |
| `ATTENDANCE_DEFAULT_PROXY_CAP` | No | `1` | Initial max successful proxy check-ins per actor per attendance day. |

---

## 4. Camera (`getUserMedia`) & Mobile PWA Notes

- **HTTPS Requirement:** Browsers only expose `navigator.mediaDevices.getUserMedia()` on `https://` origins or `http://localhost` / `http://127.0.0.1`.
- **Testing on Physical Android / iOS Devices:** To test camera capture and PWA installation from a phone on the local network, terminate TLS in front of port `8080` (e.g., Caddy, mkcert + nginx, or an HTTPS tunnel) and set `APP_COOKIE_SECURE=true`.
- **iOS Safari Caveats:** Installed iOS Home Screen PWAs run in an isolated storage context relative to Safari tabs; session cookies and camera permissions must be re-granted after installation, and `playsinline muted autoplay` attributes are required on `<video>` elements.
