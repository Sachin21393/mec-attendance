# Workforce Attendance PWA

Mobile-first worker attendance PWA and responsive admin dashboard built with Java 21, Spring Boot 3.2, PostgreSQL, Flyway, and vanilla HTML5/CSS3/ES modules.

## Documentation
- [Architecture](docs/architecture.md)
- [Technical Decisions & ADRs](docs/decisions.md)
- [Local Development & Environment Variables](docs/local-development.md)
- [Biometrics Setup, Calibration & Liveness Limitations](docs/biometrics-calibration.md)
- [PWA Hardening & Device QA Guide](docs/pwa-device-qa.md)
- [Production Deployment, Backups & Operations](docs/deployment-and-operations.md)

## Quick Start (Local)

```powershell
# 1. Start PostgreSQL 16
docker compose up -d postgres

# 2. Run all automated tests (H2 PostgreSQL-compatibility mode)
.\mvnw.cmd test

# 3. Start application locally (mock biometrics enabled by default)
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
```

### Demo Entry Points (Local Profile)
- Portal: `http://localhost:8080/`
- Worker PWA: `http://localhost:8080/worker/` (demo invitation code: `JOIN-W009-DEMO`)
- Admin Dashboard: `http://localhost:8080/admin/` (default local dev credentials in `application-local.yml`: `admin` / `ChangeMeLocalDev123!`)
- Health check: `http://localhost:8080/actuator/health`
- OpenAPI / Swagger UI: `http://localhost:8080/swagger-ui.html`
