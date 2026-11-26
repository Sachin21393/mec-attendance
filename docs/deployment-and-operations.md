# Production Deployment, Observability & Operations Runbook

## 1. Pre-Deployment Legal & Privacy Gate

> **IMPORTANT:** Do **not** enable `ATTENDANCE_PRODUCTION_BIOMETRICS_ENABLED=true` with real worker biometric data until:
> 1. Legal/privacy review has approved the lawful basis, notices/consent wording, retention period, deletion workflow, and non-biometric manual fallback under applicable Indian law and any other applicable jurisdiction.
> 2. Representative threshold calibration (FAR/FRR) is completed per [biometrics-calibration.md](biometrics-calibration.md).
> 3. Presentation Attack Detection (liveness/anti-spoofing) requirements are signed off by security leadership.

---

## 2A. Deploying on Render Free Tier (Blueprint)

This repository includes [render.yaml](../render.yaml) and [deploy/docker-entrypoint.sh](../deploy/docker-entrypoint.sh) for one-step Blueprint deployment on Render:

1. Push this repository to a GitHub/GitLab repository.
2. In the Render Dashboard, choose **New + → Blueprint** and connect the repository.
3. Render reads `render.yaml`, creates `workforce-attendance` (Docker Free Web Service) and `workforce-attendance-db` (Free PostgreSQL), downloads the YuNet/SFace ONNX models during image build, converts `DATABASE_URL` into JDBC settings at startup, and generates `BIOMETRICS_ENCRYPTION_KEY_B64`.
4. When prompted for `ADMIN_BOOTSTRAP_PASSWORD`, enter a strong administrator password.
5. After deploy finishes (`GET /actuator/health` → `{"status":"UP"}`), open `https://<your-service>.onrender.com/admin/` to register workers, and set `ADMIN_BOOTSTRAP_ENABLED=false` in the Render Environment tab once your initial admin user is created.

> **Free Tier Caveats:** Render free web services spin down after 15 minutes of inactivity (~30–60s cold start), and Render Free PostgreSQL databases expire after 30 days unless upgraded or backed up with `pg_dump`.

---

## 2. Production Deployment Steps

1. **Provision PostgreSQL 16** with TLS enabled and automated encrypted snapshots.
2. **Generate Secrets** (store in environment/secret manager, never in Git):
   - `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`
   - `BIOMETRICS_ENCRYPTION_KEY_B64` (32 random bytes, Base64-encoded: `openssl rand -base64 32`)
   - `ADMIN_BOOTSTRAP_PASSWORD` (for initial bootstrap only; set `ADMIN_BOOTSTRAP_ENABLED=false` after first login)
3. **Place Licensed ONNX Models** (if `ATTENDANCE_BIOMETRICS_PROVIDER=onnx`):
   - `models/face_detection_yunet_2023mar.onnx` (MIT)
   - `models/face_recognition_sface_2021dec.onnx` (Apache-2.0)
4. **Terminate HTTPS** using Caddy (`docker compose --profile https up -d`) or a managed TLS load balancer, with `APP_COOKIE_SECURE=true` and `SPRING_PROFILES_ACTIVE=prod`.
5. **Verify Health & Migrations:**
   - `GET /actuator/health` returns `{"status":"UP"}`
   - Flyway applies `db/migration/V1__init_schema.sql`, `V3__admin_sessions.sql`, `V4__biometric_verification_tokens.sql` (`db/seed` is excluded in `prod`).

---

## 3. Observability & Alerting

- **Structured Logs:** Configured in `logback-spring.xml` with ISO-8601 UTC timestamps and `correlationId=%X{correlationId}`. Raw face images, embeddings, passwords, and session tokens are never logged.
- **Health Checks:** `/actuator/health/liveness` and `/actuator/health/readiness` enabled in `application-prod.yml`.
- **Recommended Alerts:**
  - HTTP 5xx spike or `/actuator/health` non-200
  - Surge in `ADMIN_LOGIN_FAILED` or `BIOMETRIC_VERIFY_FAILED` audit events
  - Database connection pool saturation or disk space < 20%

---

## 4. Database Backup, Restore & Rollback

### Backup (`pg_dump`)

```bash
pg_dump --format=custom --no-owner --file="backup_$(date +%Y%m%d_%H%M%S).dump" "$DB_URL"
```

### Restore Verification (`pg_restore` into staging DB)

```bash
pg_restore --clean --if-exists --no-owner --dbname="$STAGING_DB_URL" backup_YYYYMMDD_HHMMSS.dump
```

Always verify row counts in `workers`, `attendance_records`, `work_calendar`, and `audit_events` after restore tests.

### Application Rollback

1. Take a pre-deploy `pg_dump`.
2. Deploy the previous container tag (`docker compose pull && docker compose up -d app`).
3. Never delete or edit applied Flyway migrations; issue forward-fixing SQL migrations if schema changes are needed.

---

## 5. Key Rotation & Model Updates

- **Biometric Encryption Key Rotation:** Introduce a new key version (`BIOMETRICS_KEY_VERSION=v2`), keep previous key accessible in a rotation job to decrypt `key_version=v1` rows, re-encrypt with `v2`, and update `key_version`.
- **Model Updates:** Any change to `face_detection_yunet` or `face_recognition_sface` invalidates prior embeddings; schedule a controlled re-enrollment or dual-template migration and re-calibrate `faceMatchThreshold`.

---

## 6. Production Readiness Checklist

- [ ] `SPRING_PROFILES_ACTIVE=prod`, `APP_COOKIE_SECURE=true`, HTTPS enforced
- [ ] Unique secrets injected via secret manager (`BIOMETRICS_ENCRYPTION_KEY_B64`, `DB_PASSWORD`)
- [ ] `ADMIN_BOOTSTRAP_ENABLED=false` after initial admin provisioning
- [ ] Automated `pg_dump` backups + tested restore drill
- [ ] Monitoring & alerts configured on `/actuator/health` and error rates
- [ ] CI (`./mvnw -B clean verify`) green on release commit
- [ ] Legal/privacy approval & PAD/liveness sign-off obtained before `ATTENDANCE_PRODUCTION_BIOMETRICS_ENABLED=true`
