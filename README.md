# Workforce Attendance PWA

Mobile-first worker attendance PWA and responsive admin dashboard built with Java 21, Spring Boot 3.2, PostgreSQL, Flyway, and vanilla HTML/CSS/ES modules.

## Documentation
- [Architecture](docs/architecture.md)
- [Technical Decisions & ADRs](docs/decisions.md)
- [Local Development & Environment Variables](docs/local-development.md)

## Quick Start (Local)

```powershell
# Start PostgreSQL 16
docker compose up -d postgres

# Run automated tests (H2 PostgreSQL-compatibility mode by default)
.\mvnw.cmd test

# Start application with local profile (mock biometrics enabled by default)
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
```

- Health check: `http://localhost:8080/actuator/health`
- OpenAPI spec: `http://localhost:8080/v3/api-docs`
- Swagger UI: `http://localhost:8080/swagger-ui.html`
