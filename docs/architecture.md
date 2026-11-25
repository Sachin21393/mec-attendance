# Workforce Attendance PWA — Architecture

## 1. Repository Discovery Summary

Inspection of `c:\Users\jangi\Desktop\mecatt` on 2026-11-25 confirmed:

- **Repository state:** Greenfield workspace with no pre-existing source code, build files, database migrations, or Git history.
- **Available local toolchain:**
  - Java `21.0.10` LTS (`java` and `javac`)
  - Docker `29.1.3`
  - Node.js `v24.16.0`
  - Git `2.50.1`
- **Absent from global `PATH`:** `mvn`, `gradle`, and `psql`. PR 1 will include the **Maven Wrapper** (`mvnw` / `mvnw.cmd`) and a **Docker Compose** definition for PostgreSQL 16 so the project builds and runs reproducibly without global Maven or PostgreSQL installations.

---

## 2. System Overview

The system is a **modular monolith** built on **Java 21** and **Spring Boot 3.x**, backed by **PostgreSQL 16** with **Flyway** migrations, serving both:

1. **Worker PWA** (`src/main/resources/static/worker/`): Mobile-first, installable Progressive Web App for one-time registration, face check-in, and policy-controlled proxy check-in.
2. **Admin Dashboard** (`src/main/resources/static/admin/`): Responsive web console for month-wise attendance grid inspection, worker lifecycle & invitation management, calendar/shift/proxy settings, CSV export, and audit log review.

```text
+-----------------------------------------------------------------------+
|                       Browser / Mobile PWA Client                     |
|  Worker PWA (HTML/CSS/ES Modules)   |  Admin Dashboard (HTML/CSS/JS)  |
|  - getUserMedia() camera capture    |  - Month grid & KPIs            |
|  - Service Worker (static only)     |  - Calendar & Settings editor   |
+-------------------+-----------------+----------------+----------------+
                    | HTTPS (Cookies: HttpOnly, SameSite, CSRF)
                    v
+-----------------------------------------------------------------------+
|                 Spring Boot 3.x Modular Monolith (/api/v1)            |
|                                                                       |
|  +--------+  +---------+  +------------+  +------------+              |
|  |  auth  |  | workers |  | biometrics |  | attendance |              |
|  +--------+  +---------+  +------------+  +------------+              |
|  +----------+  +---------+  +---------+  +--------+                   |
|  | settings |  | reports |  |  audit  |  | common |                   |
|  +----------+  +---------+  +---------+  +--------+                   |
|                                                                       |
|  Biometrics Abstraction: FaceRecognitionProvider (Mock | ONNX)        |
+-------------------+---------------------------------------------------+
                    | JDBC / JPA / Flyway
                    v
+-----------------------------------------------------------------------+
|                           PostgreSQL 16                               |
|  workers, worker_invitations, worker_biometrics (AES-GCM encrypted),  |
|  worker_sessions, attendance_records, work_calendar,                  |
|  attendance_settings, audit_events, admin_users, consent_records,     |
|  attendance_corrections                                               |
+-----------------------------------------------------------------------+
```

---

## 3. Package & Module Structure

```text
src/main/java/com/example/attendance/
  auth/         Admin login/logout, worker session/cookie auth, RBAC, rate limiting
  workers/      Worker profiles, one-time invitations, registration, device revocation
  biometrics/   FaceRecognitionProvider SPI, frame validation, encryption, matching
  attendance/   Check-in flow, daily uniqueness, idempotency, proxy cap, status calc
  settings/     Monthly work calendar, shifts, grace period, cutoff, timezone, proxy cap
  reports/      Monthly aggregations and UTF-8 CSV export with formula-injection escaping
  audit/        Append-only audit event recording and admin query filters
  common/       Exception handling, ErrorResponse DTOs, correlation IDs, time helpers

src/main/resources/
  db/migration/ Flyway SQL migrations (V1__..., V2__..., etc.)
  static/
    index.html
    manifest.webmanifest
    service-worker.js
    shared/     api.js, auth.js, components.js, styles.css
    worker/     index.html, worker.css, worker.js
    admin/      index.html, admin.css, admin.js
    assets/     App icons, SVG status icons
```

### Module Boundaries & Rules

- **No cross-module leakage of biometrics:** Only `biometrics` reads/decrypts `worker_biometrics`. Ordinary `workers`, `attendance`, and `admin` DTOs never include raw embeddings or images.
- **Server-authoritative identity & time:** Worker actor identity comes exclusively from server-verified `worker_sessions`; timestamps use server UTC (`Instant.now()`) projected onto the workplace timezone (`ZoneId`).
- **Append-only audit trail:** `audit_events` has no delete/update endpoints or cascading deletes from operational tables.

---

## 4. Core Data Model

| Table | Purpose | Key Constraints |
|---|---|---|
| `workers` | Worker profile & lifecycle (`INVITED`, `REGISTERED`, `DISABLED`) | `worker_code` UNIQUE |
| `worker_invitations` | Admin-issued one-time registration codes | `code_hash` UNIQUE, single-use expiry |
| `consent_records` | Versioned worker biometric/privacy consent | FK to `workers` |
| `worker_biometrics` | AES-GCM encrypted face template + model metadata | `worker_id` UNIQUE FK |
| `worker_sessions` | Hashed worker session/device tokens & expiry/revocation | `token_hash` UNIQUE |
| `admin_users` | Admin credentials (BCrypt/Argon2) and role (`ADMIN`, `TECH_ADMIN`) | `username` UNIQUE |
| `attendance_records` | Daily check-in (`subject_worker_id`, `actor_worker_id`, method, status) | UNIQUE `(subject_worker_id, attendance_date)`, `idempotency_key` |
| `attendance_corrections` | Audited admin overrides with reason & before/after state | FK to `attendance_records` / `workers` |
| `work_calendar` | Per-date `WORKING`, `WEEKLY_OFF`, `HOLIDAY`, shift/grace/cutoff overrides | `calendar_date` UNIQUE |
| `attendance_settings` | Workplace timezone, default shift/grace/cutoff, proxy enabled & daily cap | Versioned / effective config |
| `audit_events` | Immutable security & operational audit log with `correlation_id` | Append-only |

---

## 5. API Contract Overview (`/api/v1`)

- **Worker & Registration:**
  - `POST /api/v1/worker-invitations/validate`
  - `POST /api/v1/workers/register`
  - `GET /api/v1/workers/me`
  - `POST /api/v1/workers/logout`
  - `POST /api/v1/workers/device/revoke`
- **Biometrics & Attendance:**
  - `POST /api/v1/biometrics/verify` (returns short-lived signed/stored verification token; never returns embedding)
  - `POST /api/v1/attendance/check-in` (accepts verification token + `Idempotency-Key` + mode `SELF` / `PROXY`)
  - `GET /api/v1/attendance/today`
  - `GET /api/v1/attendance/history?month=YYYY-MM`
- **Admin:**
  - `POST /api/v1/admin/auth/login` / `POST /api/v1/admin/auth/logout`
  - `GET /api/v1/admin/dashboard?month=YYYY-MM`
  - `GET /api/v1/admin/attendance?month=YYYY-MM&page=...`
  - `GET /api/v1/admin/attendance/{id}` / `POST /api/v1/admin/attendance/{id}/correct`
  - `GET /api/v1/admin/workers` / `POST /api/v1/admin/workers` / `PATCH /api/v1/admin/workers/{id}`
  - `POST /api/v1/admin/workers/{id}/disable` / `POST /api/v1/admin/workers/{id}/biometric-reset`
  - `GET /api/v1/admin/calendar?month=YYYY-MM` / `PUT /api/v1/admin/calendar?month=YYYY-MM`
  - `GET /api/v1/admin/settings` / `PUT /api/v1/admin/settings`
  - `GET /api/v1/admin/reports/attendance.csv?month=YYYY-MM`
  - `GET /api/v1/admin/audit-events`

All error responses use a consistent JSON envelope containing `code`, `message`, `correlationId`, and `timestamp`.

---

## 6. Security, Privacy & PWA Controls

- **Transport & Cookies:** HTTPS required in all non-localhost environments. Sessions stored in `HttpOnly`, `Secure` (when HTTPS), `SameSite=Lax` cookies with CSRF protection on state-changing routes.
- **Rate Limiting:** Applied to admin login, invitation validation, `/biometrics/verify`, and `/attendance/check-in`.
- **Transient Camera Frames:** Captured images are validated (MIME, magic bytes, decoded dimensions, max byte size), processed in memory, and immediately discarded. Neither raw frames nor embeddings are logged or cached.
- **Service Worker Caching Policy:** Caches only static shell assets (`/shared/*`, `/worker/*`, `/admin/*`, icons). Network-only for all `/api/**` calls; offline check-in attempts explicitly report failure and never queue or fake success.
