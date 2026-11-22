# Staging runbook — Baked & Browned

**Status:** documentation only (2026-11-22). The repository has no deployment artifacts yet (section 2), so this runbook says what to provision, in what order, and how to prove it works. It contains no commands for tooling the repository does not have. Everything stated about the application was checked against the code and the ADRs it cites.

Read it with [launch-readiness.md](launch-readiness.md), which lists every setting, and [adr/0030-production-readiness-check.md](adr/0030-production-readiness-check.md), which explains why the application refuses to start on an unsafe configuration.

## 1. What staging is for, and its limits

Staging is a second environment run with `SPRING_PROFILES_ACTIVE=prod`, behind a real HTTPS domain, with synthetic data. It exists to prove what cannot be proved on a laptop:

- `Secure` cookies and same-origin hosting behind a real TLS proxy;
- real Safari (iOS and macOS) and Android Chrome (QA-01, ADR-0023);
- the SMS provider, from a fixed outgoing IP address;
- the `prod` start-up checks, a backup and restore, and a restart.

**Constraints that follow from the code (decide these before you start):**

| Constraint | Why | Consequence for staging |
|---|---|---|
| Staging runs the `prod` profile | ADR-0030 applies to every environment run with `prod`, staging included. | HTTPS origins only, no `localhost`, a persistent JWT key, an absolute proof directory. |
| **SSLCommerz cannot use its sandbox under `prod`** | The readiness check refuses an enabled gateway whose `base-url` is the sandbox (the shipped default) or unset. | Staging runs with the gateway **disabled** (cash on delivery and manual bKash, Nagad or bank accounts). Online payment is proved elsewhere: the ADR-0018 contract suite against the sandbox from a dev environment, and one low-value live payment at launch. |
| **An SMS sender is mandatory** | In `prod` the only `SmsSender` is the SSL Wireless adapter; the application refuses to start without it enabled, with a token and a sender id. | Staging needs the real account, and its outgoing IP allowlisted. Test messages are real: send only to numbers whose owners agreed. |
| A fake adapter cannot be loaded | `prod` combined with `dev` or `test` is refused. | Never add a second profile to staging. |
| One API instance | In-process schedulers and rate limits assume it (`docs/architecture.md`). | Deploy by stopping the old instance, then starting the new one. |

## 2. What the repository provides, and what it does not

**Provides:**
- `infra/docker/compose.yaml`: a **local** PostgreSQL 17.12 only, bound to `127.0.0.1`. It is not a staging database.
- `.github/workflows/ci.yml`: build, test, lint, the spec check and `docker compose config`. It publishes nothing.
- `backend/` builds an executable jar (`backend/target/backend-0.0.1-SNAPSHOT.jar` per the README), Java 25. `frontend/` builds static files with `npm run build`; confirm the output folder after building.
- Health endpoints, public by design: `GET /actuator/health`, plus `/actuator/health/liveness` and `/readiness` (probes are enabled).

**Does not provide (to be created before or during staging, outside this documentation-only change):**
- a Dockerfile or image build, an image registry or publish step, a deploy workflow;
- a reverse proxy and TLS configuration, a production compose file or runtime manifest;
- backup and restore scripts, secret-store wiring, process supervision;
- a way to set up the catalogue, delivery zones and delivery slots from the web app: the admin API has `/admin/products`, `/admin/delivery-zones` and `/admin/delivery-slots`, but the admin screens in `app.routes.ts` are the dashboard, orders, payments, refunds and reconciliation, with none for products, zones or slots. **Confirm how these will be loaded** (see section 12).

## 3. Prerequisites

| # | Item | Owner | Notes |
|---|---|---|---|
| 1 | HTTPS subdomain for staging, with a valid certificate | You | One origin serves the web app and `/api` (same-origin; refresh cookies depend on it). |
| 2 | Host or platform for the API, the proxy and PostgreSQL | You | A private network or managed PostgreSQL 17; the database is never exposed publicly. |
| 3 | **Fixed outgoing IP** of the API host | You | Given to SSL Wireless for allowlisting; otherwise the provider rejects with error 4003. |
| 4 | SSL Wireless ISMS Plus account: API token, sender id, answers to the ADR-0027 questions | Business | Token only from the secret store. |
| 5 | Secret store | You | See section 4; secrets never go in the image, the repository or a file in the repository. |
| 6 | A private, backed-up volume for payment proofs | You | Outside any web root; writable by the API process. |
| 7 | Two real people (or test identities) for two ADMIN accounts | Business | A refund's requester can never approve it. |
| 8 | Test devices: iPhone (Safari), Mac Safari, an Android phone (Chrome) | You | For section 8. |
| 9 | Agreed test phone numbers for real SMS | You | Owners must consent. |

## 4. Configuration for staging

Settings are environment variables (Spring relaxed binding). **Secrets come only from the secret store.** Names and rules are those of [launch-readiness.md](launch-readiness.md); staging values below are the staging-specific part.

| Setting | Staging value | Checked at start-up |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod` only | Refused if combined with `dev` or `test` |
| `SPRING_DATASOURCE_URL`, `_USERNAME`, `_PASSWORD` | The staging database; a dedicated user | Connection fails at start otherwise |
| `APP_SECURITY_JWT_PRIVATE_KEY` | A **staging-only** PKCS#8 RSA key of 2048 bits or more (the README shows `openssl genpkey`). Never reuse the production key. | Refused if missing, unparseable, or if `allow-ephemeral-key` is true |
| `APP_SECURITY_ALLOWED_ORIGINS` | Exactly the staging site's `https://` origin | Refused if empty, not `https`, or local |
| `app.security.secure-cookies` | Leave the default `true` | Refused if `false` |
| `APP_PAYMENTS_ONLINE_RETURN_URL` | `https://<staging-host>/orders/{orderId}/payment` | Refused if not `https` or local |
| `APP_PAYMENTS_ONLINE_API_BASE_URL` | `https://<staging-host>` (the public origin of the API) | Refused if not `https` or local |
| `APP_PAYMENTS_PROOF_STORAGE_DIRECTORY` | The absolute path of the proof volume | Refused if not absolute |
| `APP_PAYMENTS_SSLCOMMERZ_ENABLED` | `false` (see section 1) | A warning that only cash on delivery (and manual accounts) is available, unless manual accounts are configured |
| `app.payments.manual.accounts` | Test receiving accounts, if manual payments are to be exercised | Not checked |
| `APP_NOTIFICATIONS_SMS_SSL_WIRELESS_ENABLED` | `true` | Refused otherwise |
| `APP_NOTIFICATIONS_SMS_SSL_WIRELESS_API_TOKEN`, `_SENDER_ID` | From the account | Refused if blank; the adapter also checks https and the sender id length later in start-up |

A refused configuration stops the application **before** it opens a database connection or runs a migration, and the message names each setting (never its value). Treat that as a test in its own right (section 8, check 2).

## 5. Build and package (requirements, not commands)

- **API:** build the jar from a clean checkout of a specific commit, with Java 25. CI's command is `./mvnw -B -ntp verify` in `backend/`. Record the commit SHA that is deployed.
- **Web app:** `npm ci` then `npm run build` in `frontend/`. The result is static files; the web app talks to `/api` on its own origin.
- **Runtime image or unit (to be created):** pinned base versions, a non-root user, the `prod` profile, a health check against `/actuator/health/readiness`, and no secrets inside. `docs/architecture.md` states the same requirements.

## 6. Network and reverse proxy requirements

- TLS terminates at the proxy. HTTP is redirected to HTTPS or refused.
- The proxy serves the web app's static files and forwards `/api` to the API on the same origin.
- **Forwarded headers.** The API honours `X-Forwarded-For` and `X-Forwarded-Proto` only from trusted internal proxies (`server.forward-headers-strategy: native`, Tomcat's remote-IP handling). The proxy must send them, and its address must be one the API trusts; otherwise every request looks like it comes from the proxy and the per-IP rate limits (login 10 per 5 minutes, register 5 per 15 minutes) hit all users together. Check this in section 8. If the proxy is not on a private address, configure Spring Boot's trusted-proxy setting deliberately.
- **Upload size.** Manual-payment proofs are at most 5 MB (the multipart limit is 5 MB per file and 6 MB per request). Allow at least 6 MB through the proxy, so a valid upload is not refused before it reaches the API.
- **Public routes the proxy must pass through,** because the application protects them itself: `POST /api/v1/payments/webhooks/{provider}` (gateway callbacks) and `GET /api/v1/payments/{orderId}/return`. Staging has no gateway enabled, so only the first matters at launch.
- The API's port and the database are reachable only from the proxy and the API host, respectively. `/actuator/health` is public by design; expose only what the platform needs.
- Do not add any caching of `/api` responses.

## 7. Deploy and start order

1. **Database.** PostgreSQL 17, empty, reachable from the API host only. Confirm backups are configured before it holds anything you care about.
2. **Secrets and volume.** Secrets in place, proof volume mounted and writable by the API process.
3. **API.** Start with the `prod` profile and the settings in section 4. On the first start Flyway applies V1 to V17; confirm 17 successful rows in `flyway_schema_history`. Wait for `/actuator/health/readiness` to report `UP`. Read the start-up log for warnings: the cash-on-delivery-only warning is expected on staging; an ephemeral-key warning is not.
4. **Proxy and web app.** Start the proxy with the static files and the `/api` route.
5. **Provision the first admin,** only after the API is healthy and idle, with the one-off command described in the README ("Provisioning admins"): the same jar, `--spring.profiles.active=provision-admin`, passwords as secret files with mode `0400` or `0600`, removed after the run. Then provision the **second** admin with `ADMIN_PROVISION_MODE=additional`. Never type passwords inline, and do not run the command while the API is starting.
6. **Load the catalogue, delivery zones and slots** (see section 12). The seeded delivery zones are inactive and have no fee.

**Deploying a new version:** stop the old instance, then start the new one. Overlapping instances risk, at most, one extra sign-in for a user (ADR-0031, ADR-0032); never run two at once on purpose.

## 8. Verification

Work through these in order and record the result of each (section 11). "Expect" is the pass condition.

| # | Check | Expect |
|---|---|---|
| 1 | `GET /actuator/health` and `/actuator/health/readiness` through the proxy | `UP` over HTTPS |
| 2 | **Start-up refusal drill** (do this once, on a throwaway start): set `APP_SECURITY_ALLOWED_ORIGINS` to `http://localhost:4200` and start the API against an **empty** database | It refuses, naming the setting and not its value; the database has no tables. Then restore the setting. |
| 3 | The same for a missing JWT key, `…SSL_WIRELESS_ENABLED=false`, and `SPRING_PROFILES_ACTIVE=prod,dev` | Each refuses before the database is touched |
| 4 | Register a customer, sign in, open **My orders** | Works over HTTPS |
| 5 | Browser dev tools: cookies `refresh_token` and `XSRF-TOKEN` | Both marked `Secure`; `refresh_token` is `HttpOnly`, `SameSite=Strict`, path `/api/v1/auth` |
| 6 | Stay signed in for more than 15 minutes, then reload | Still signed in (refresh works through the proxy) |
| 7 | **Two-tab logout:** sign in, open a second tab, log out in the first, make a request in the second | The second tab is signed out at its next request (it was verified on a laptop in dev; repeat here) |
| 8 | **Real devices:** sign-in, refresh after a reload, checkout, and the return from a payment page on iPhone Safari, Mac Safari and Android Chrome (QA-01, ADR-0023) | All work; record each browser and version |
| 9 | Restart the API | Signed-in users stay signed in (the JWT key is stable) |
| 10 | Place an order by cash on delivery; with a manual account, upload a proof just under 5 MB | The order is created; the proof is accepted, stored on the proof volume, and appears in the volume's backup |
| 11 | An ADMIN moves an order to delivery; the **second** ADMIN approves a test refund requested by the first | The refund is recorded; the requester cannot approve their own |
| 12 | One real SMS to an agreed number | Delivered, not rejected (error 4003 means the IP is not allowlisted). Notices end as SENT, FAILED or EXPIRED. |
| 13 | Rate limit by real client IP: from two different networks, exceed the login limit from one | Only that client is limited, not the other (proves forwarded headers work) |
| 14 | Disable an account with the operator command, then try to use its session | Its access tokens are refused from the next request (ADR-0028, ADR-0032) |
| 15 | The §4 launch-day smoke test in [launch-readiness.md](launch-readiness.md) | Passes |

## 9. Backup and restore drill

The target is the database **and** the proof volume restored together; the point is a drill that works, not a plan.

1. With staging holding test orders and at least one proof file, take a backup of the database and of the proof volume at the same time.
2. Restore both into a **separate** scratch environment (a different database and volume; never over a running staging).
3. Start the API against the restored copy with the `prod` profile and confirm it is healthy and migrated to V17.
4. Open a restored order that has a proof, and check the file is served.
5. Record how long the restore took, and what the defined retention is (architecture: daily backups with a tested restore).

Backups that have never been restored do not count.

## 10. Failure and rollback

- **Start-up refusal:** read the message; it names every setting to fix. No data was touched.
- **Late refusals:** the SMS adapter checks the address scheme and sender id length after the migrations may have run; an unreachable database fails at connection. Fix the setting and restart.
- **Roll back the application** to the previous release: V16 and V17 are additive and the previous code ignores their columns and index. Sessions created by the older release get token version 0; where an account's version is above 0 those users sign in again (fail closed).
- **Roll back data:** restore from backup (section 9). A migration is never edited after it has been applied anywhere.
- **Do not** run `docker system prune`, delete volumes, or reuse a production key or database in staging.

## 11. Recording results

Create `docs/qa/<date>-staging-verification.md` in the format of the earlier QA reports: environment, commit SHA, one row per check in section 8 with PASS, FAIL or BLOCKED, the browser and device versions, and any finding. Screenshots stay out of Git unless they are redacted. Do not record tokens, passwords, keys or full phone numbers.

**Staging is done when** checks 1 to 15 pass (or each failure has an owner and a decision), the backup was restored once, and the results are recorded.

## 12. Open decisions and gaps

1. **Online payment on staging.** Staging cannot use the SSLCommerz sandbox under `prod` (section 1). Confirm that online payment is proved by the dev-environment contract suite plus one live low-value payment at launch, or decide on another route.
2. **Loading the catalogue, delivery zones and slots.** There is no admin screen for them; only the admin API. Decide who loads them and how, and whether a screen is needed before launch.
3. **Deployment artifacts.** Decide whether the next step is a Dockerfile and a staging compose draft in the repository, or keeping infrastructure outside it.
4. **Admin MFA, alerting and a checkout load test** appear under "production hardening" in `docs/architecture.md` but not in the launch checklist. Decide whether they gate launch.
5. **Shared office IP.** If staff share one public IP, the login limit (10 per 5 minutes per IP) will apply to all of them together.
6. **The per-request session query** should be re-checked with `EXPLAIN` on the staging database once it holds realistic volume (ADR-0032, amendment).
