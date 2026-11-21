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

## Deploying to production

Run with `SPRING_PROFILES_ACTIVE=prod`, and work through [docs/launch-readiness.md](docs/launch-readiness.md)
first. Under `prod` the app refuses to start while a local-development default is still set: localhost
origins or return URLs, a relative proof directory, insecure cookies, an ephemeral signing key, or an
enabled SSLCommerz gateway still pointing at the sandbox. The
message names every setting to fix (ADR-0030).

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
- Refresh tokens: opaque, stored as SHA-256 hashes, rotated on every refresh. Reusing a rotated token revokes its whole family.
- QA-01 (ADR-0023 rev 4): the web app sends its refresh with `keepalive` and leaves an in-flight marker, so a reload, navigation or tab close during a refresh no longer signs the user out. A refresh response lost in the network still does (ADR-0026).
- Sessions (ADR-0025): each refresh extends a session by the role's idle lifetime, never past its absolute end after login. Customers: 14 days idle, 30 days absolute. Admins: 8 hours idle, 12 hours absolute (`app.security.sessions.*`).
- `refresh_token` cookie: `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth`. `XSRF-TOKEN` cookie: readable, echoed by the SPA in `X-CSRF-Token` on refresh and logout.
- The SPA must send at most one refresh at a time. Two parallel refreshes with the same token count as reuse and end the session.
- Per-IP limits on login, register and refresh. Behind the reverse proxy, client IPs come from `X-Forwarded-For` set by a trusted internal proxy (`server.forward-headers-strategy: native`).

### Provisioning admins

Public registration only ever creates customers. ADMINs are created by a one-off command
(`--spring.profiles.active=provision-admin`). It:
- starts no web server, scheduled jobs or notification delivery, so it needs no SMS provider;
- exits non-zero on any refusal;
- refuses passwords given as command-line arguments.

There is no API for creating admins or changing roles. Every attempt is recorded exactly once in
`audit_events` (`admin.provisioned`, `admin.provisioning_refused` or `admin.provisioning_failed`),
without passwords. If the success record cannot be written, the account is rolled back (ADR-0013, ADR-0024).

Run it only from the production platform's one-off task or exec mechanism, as an authorized operator,
with passwords injected as secret files. Never type them inline.

**First admin** (refused if an ADMIN already exists):

```sh
# /run/secrets/admin_password holds the new admin's password (12-128 characters)
ADMIN_FULL_NAME='Store Admin' ADMIN_PHONE='+8801XXXXXXXXX' ADMIN_EMAIL='admin@example.com' ADMIN_PASSWORD_FILE=/run/secrets/admin_password java -jar backend/target/backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=provision-admin
```

**Additional admin**, authorized by an existing enabled ADMIN (needed because whoever requests a
refund can never approve it):

```sh
ADMIN_PROVISION_MODE=additional PROVISIONER_IDENTIFIER='+8801XXXXXXXXX' PROVISIONER_PASSWORD_FILE=/run/secrets/provisioner_password ADMIN_FULL_NAME='Shift Manager' ADMIN_PHONE='+8801YYYYYYYYY' ADMIN_PASSWORD_FILE=/run/secrets/admin_password java -jar backend/target/backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=provision-admin
```

**Disable or re-enable an account** (ADR-0029), authorized the same way. `ACCOUNT_IDENTIFIER` is the
account's phone (`+880…`) or email; `ACCOUNT_CHANGE_REASON` (3–500 characters) is recorded in the audit:

```sh
ADMIN_PROVISION_MODE=disable PROVISIONER_IDENTIFIER='+8801XXXXXXXXX' PROVISIONER_PASSWORD_FILE=/run/secrets/provisioner_password ACCOUNT_IDENTIFIER='+8801ZZZZZZZZZ' ACCOUNT_CHANGE_REASON='Phone reported stolen' java -jar backend/target/backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=provision-admin
# later, if appropriate:
ADMIN_PROVISION_MODE=enable PROVISIONER_IDENTIFIER='+8801XXXXXXXXX' PROVISIONER_PASSWORD_FILE=/run/secrets/provisioner_password ACCOUNT_IDENTIFIER='+8801ZZZZZZZZZ' ACCOUNT_CHANGE_REASON='Phone recovered' java -jar backend/target/backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=provision-admin
```

- **Disabling** revokes every session of the account at once. Its access tokens are refused from its
  next request (ADR-0028).
- **Re-enabling** never revives a session: the user signs in again, so a token stolen before the
  disable stays dead.
- **Refusals:** the last enabled ADMIN cannot be disabled (provision another first), and neither can an
  unknown or already disabled account.
- **Audit:** every attempt is recorded once (`account.disabled`, `account.enabled`,
  `account.status_change_refused` or `account.status_change_failed`).
- **No demotion:** there is no command to demote an ADMIN; disable the account instead.

Notes:
- The production database and JWT key settings apply as for the web app (`SPRING_DATASOURCE_*`,
  `APP_SECURITY_JWT_PRIVATE_KEY`).
- Passwords are accepted only from `*_FILE` secret files (preferred) or process environment variables.
  Command-line arguments, `-D` system properties, `spring.application.json` and config files are refused.
- Secret files must be:
  - delivered by the platform's secret mechanism (orchestrator secret or tmpfs mount), never baked into
    an image or committed;
  - owned by the account running the command, with mode `0400`/`0600` (no group or world access);
  - removed after the run.

  The command does not check permissions itself.
- The command refuses to run if a web server is started (do not override `spring.main.web-application-type`).
- The phone and email must be new: an existing account, a customer's included, is never made ADMIN.
- Alert on and review provisioning audit events, and review the ADMIN list periodically (ADR-0024,
  "Operational access").

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

### SMS notices (ADR-0016, ADR-0027)

Customer notices go by SMS through `notification.sms.SmsSender`. Under `dev` and `test` a
`FakeSmsSender` keeps them in memory. Production uses SSL Wireless ISMS Plus; without it, the `prod`
profile has no `SmsSender` and the web app refuses to start.

```sh
APP_NOTIFICATIONS_SMS_SSL_WIRELESS_ENABLED=true
APP_NOTIFICATIONS_SMS_SSL_WIRELESS_API_TOKEN=...      # from the secret store, never in a file
APP_NOTIFICATIONS_SMS_SSL_WIRELESS_SENDER_ID=...      # the sid assigned to the account
# Defaults: base-url https://smsplus.sslwireless.com/api/v3, connect-timeout 5s, read-timeout 15s
```

- The server's outgoing IP address must be whitelisted in the ISMS Plus portal (error 4003 otherwise).
- A notice ends as SENT (accepted by the provider), FAILED (rejected permanently, for example an
  invalid number; `failure_code` holds the provider's code) or EXPIRED (not sent within 24 hours of
  its event). Configuration errors (token, sender ID, IP whitelist) are logged at ERROR and retried,
  so no notice is lost while they are fixed.
- Enabling it under `dev` replaces the fake, for a real test message.

## Frontend shell and auth (Phase 1F)

```sh
# Full stack locally: backend with a throwaway Testcontainers database, then the SPA
cd backend && ./mvnw spring-boot:test-run      # http://localhost:8080
cd frontend && npm start                       # http://localhost:4200, proxies /api to :8080
```

- **API client:** generated by `ng-openapi-gen` from `docs/api-contract.yaml` into
  `frontend/src/app/core/api/generated/` (committed, never edited by hand). After changing the
  contract, run `npm run api:generate`. CI runs `npm run api:check` and fails if the committed
  client is out of date.
- **Access token:** kept only in memory (`AuthSession`), never in web storage. On load the app
  resumes the session from the refresh cookie when an `XSRF-TOKEN` cookie is present.
- **`authInterceptor`:** adds the bearer token to `/api/v1` calls only. An expired token is
  refreshed before the request is sent. After a 401 the app refreshes once and retries once;
  concurrent requests share one refresh.
- **Refreshes in more than one tab** are serialized with the Web Locks API, so a rotated refresh
  token is never presented twice.
- **Route guards** are for user experience only. The API enforces access.
