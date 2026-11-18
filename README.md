# Baked & Browned

Online bakery ordering platform for Dhaka, Bangladesh (BDT, Asia/Dhaka).
Specification: [`docs/`](docs/) — architecture, business rules, database, OpenAPI contract and [ADRs](docs/adr/).

## Repository layout

| Path | Contents |
|---|---|
| `backend/` | Spring Boot 4 modular monolith (Maven, Java 25) |
| `frontend/` | Angular 22 standalone app (Tailwind CSS 4, Vitest, ESLint) |
| `infra/docker/` | Docker Compose for local services (PostgreSQL 17) |
| `.github/workflows/` | CI: backend build/test, frontend format/lint/test/build, compose validation |
| `docs/` | Specification v1.1 and ADRs |

## Prerequisites

- JDK 25 (Maven is provided by `backend/mvnw`)
- Node.js 24 LTS (`frontend/.nvmrc`: 24.22.3; npm 11)
- Docker Desktop (PostgreSQL, and Testcontainers from Phase 1B)

## Common commands

```sh
# Backend: compile (warnings are errors), test, package
cd backend && ./mvnw verify

# Frontend
cd frontend
npm ci
npm start              # dev server on http://localhost:4200
npm run format:check
npm run lint
npm run test:ci
npm run build

# Local PostgreSQL
cp infra/docker/.env.example infra/docker/.env   # set POSTGRES_PASSWORD
docker compose -f infra/docker/compose.yaml up -d
```

## Pinned versions (Phase 1A bootstrap, 2026-11-17)

Policy: latest **GA** release of each approved major line at bootstrap time (no RC/milestone). Exact
JavaScript versions are locked by `frontend/package-lock.json`; CI installs with `npm ci`.
Upgrades are deliberate, reviewed changes.

| Component | Version | Source of truth |
|---|---|---|
| Java | 25 (Temurin in CI) | `backend/pom.xml` `java.version`, enforcer `[25,26)` |
| Spring Boot | 4.1.2 | `backend/pom.xml` parent (4.2 was RC only) |
| Jackson (via Boot) | 3.1.7 | Boot dependency management |
| JUnit / Mockito (via Boot) | 6.0.3 / 5.23.0 | Boot dependency management |
| Spring Security / Nimbus JOSE (via Boot) | 7.1.2 / 10.9.1 | Boot dependency management (added Phase 1D) |
| BouncyCastle `bcprov-jdk18on` | 1.84 | `backend/pom.xml` `bouncycastle.version` (Argon2; not managed by Boot) |
| Maven (wrapper) | 3.10.1 | `backend/.mvn/wrapper/maven-wrapper.properties` |
| Node.js | 24.22.3 LTS | `frontend/.nvmrc` |
| Angular (framework / CLI) | 22.2.6 / 22.2.7 | `frontend/package-lock.json` |
| TypeScript | 6.0.3 | `frontend/package-lock.json` (Angular 22 requires `>=6.0 <6.1`) |
| Tailwind CSS | 4.3.5 (`@tailwindcss/postcss`) | `frontend/package-lock.json` |
| Vitest | 5.1.0 | `frontend/package-lock.json` |
| ESLint / angular-eslint / typescript-eslint | 10.15.0 / 22.7.0 / 8.72.1 | `frontend/package-lock.json` |
| PostgreSQL | 17.12 (alpine image) | `infra/docker/compose.yaml` |
| GitHub Actions | checkout v7.0.1, setup-java v6.3.0, setup-node v7.1.0 | pinned by commit SHA in `ci.yml` |

## Phase 1A spike findings

- **Jackson 3 and money** (`MoneyJsonSerializationSpikeTest`): `BigDecimal` is written with its own
  scale (`1250.00` stays `1250.00`, but `1250` is written as `1250`), and input with 3+ decimals is
  accepted. The Phase 1C `Money` type must normalize to scale 2, and request validation must reject
  amounts with more than two decimals.
- **Hibernate 7 native PostgreSQL enums under `ddl-auto=validate`**: moved to Phase 1B, because it
  needs a running PostgreSQL (Testcontainers).

## Authentication (Phase 1D)

Configuration lives under `app.security.*` and `app.rate-limits.*` in
`backend/src/main/resources/application.yaml`. Secrets come only from the environment:

| Variable | Purpose |
|---|---|
| `APP_SECURITY_JWT_PRIVATE_KEY` | PKCS#8 PEM RSA key (>= 2048 bits) that signs access tokens. Startup fails without it. |
| `APP_SECURITY_ALLOWED_ORIGINS` | Exact `Origin` values accepted by `/auth/refresh` and `/auth/logout` (default: `http://localhost:4200`). |
| `APP_SECURITY_JWT_ALLOW_EPHEMERAL_KEY` | `true` only for local development: generates a throwaway key (sessions end on restart). Tests and `./mvnw spring-boot:test-run` set it automatically. |

Generate a key for a real environment (store it in the secret manager, never in the repository):

```sh
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out jwt-private.pem
```

- Access tokens: RS256 JWTs, 15 minutes, `kid` header, carrying only the user id and role.
- Refresh tokens: opaque, 14 days, stored as SHA-256 hashes, rotated on every refresh. Reusing a rotated token revokes its whole family.
- `refresh_token` cookie: `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth`. `XSRF-TOKEN` cookie: readable, echoed by the SPA in `X-CSRF-Token` on refresh and logout.
- The SPA must send at most one refresh at a time. Two parallel refreshes with the same token count as reuse and end the session.
- Per-IP limits on login, register and refresh. Behind the reverse proxy, client IPs come from `X-Forwarded-For` set by a trusted internal proxy (`server.forward-headers-strategy: native`).

### Provisioning the first admin

Public registration only ever creates customers. The first ADMIN is created by a one-off command
that reads credentials from the environment, starts no web server, and refuses to run if an ADMIN
already exists:

```sh
ADMIN_FULL_NAME='Store Admin' ADMIN_PHONE='+8801XXXXXXXXX' ADMIN_EMAIL='admin@example.com' \
ADMIN_PASSWORD='<12-128 characters>' APP_SECURITY_JWT_ALLOW_EPHEMERAL_KEY=true \
java -jar backend/target/backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=provision-admin
```

## Payments port, outbox and scheduled jobs (Phase 1E)

- `payment.gateway.PaymentGateway` is the provider-neutral port (ADR-0012). The only adapter so far is
  `FakePaymentGateway`, registered under the `dev` and `test` profiles. It refuses to start if `prod`
  is also active. New adapters must pass `PaymentGatewayContract` (test sources).
- `common.outbox`: call `OutboxPublisher.publish(...)` inside the business transaction. An
  `OutboxHandler` per event type delivers it after commit, at least once (handlers must be
  idempotent by event id), with exponential backoff (`app.outbox.*`).
- Scheduled jobs (`app.scheduling.enabled`, default on): outbox dispatch every 5 s, and hourly
  deletion of refresh-token families that expired more than 7 days ago. The hold-expiry job comes
  with orders in Phase 2.
