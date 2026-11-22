# Launch readiness — Baked & Browned

A checklist for the first production deployment, and for every new environment. Deployments run with `SPRING_PROFILES_ACTIVE=prod`.

**What the "If wrong" column means:**
- **refused:** the app does not start, and the message names the setting (`ProductionReadinessCheck`, ADR-0030, or the component noted).
- **warned:** the app starts and logs a warning.
- **not checked:** verify it by hand, using the "How to verify" column.

Secrets come only from the platform's secret store, never from files in the repository or the image.

## 1. Settings

### Web origin, cookies and sessions
| Setting (environment variable) | What it is | Supplied by | If wrong | How to verify |
|---|---|---|---|---|
| `APP_SECURITY_ALLOWED_ORIGINS` | Exact https origins of the web app, allowed to refresh and log out | Operator | refused (https only, not localhost) | Sign in on the real site, wait more than 15 min, and keep using it without being signed out |
| `app.security.secure-cookies` | `Secure` flag on the refresh and XSRF cookies | Default `true` | refused if `false` | Browser dev tools: both cookies show *Secure* |
| `APP_SECURITY_JWT_PRIVATE_KEY` | PKCS#8 PEM RSA key (2048 bits or more) that signs access tokens | Secret store | refused if missing or not a valid key (before the database is touched); refused if `allow-ephemeral-key=true` | Restart the app: signed-in users stay signed in |
| `app.security.jwt.issuer` / `audience` | Token issuer and audience | Defaults | not checked | Match the production API domain |
| Same-origin hosting | The web app and `/api` are served from one origin, behind HTTPS | Operator | not checked | Open the site and check that API calls go to the same origin and refresh cookies are sent |
| Staging and other `prod` environments | Every environment run with the `prod` profile, staging included, needs a real HTTPS domain: the readiness check refuses `http://` and localhost origins and addresses | Operator | refused | Plan staging on its own HTTPS subdomain before the first deploy |
| Trusted proxy | `X-Forwarded-For/-Proto` honoured only from internal proxies (`server.forward-headers-strategy: native`) | Platform | not checked | Rate limits apply per real client IP |

### Database and files
| Setting | What it is | Supplied by | If wrong | How to verify |
|---|---|---|---|---|
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | PostgreSQL 17 | Operator, secret store | startup fails to connect | The Flyway migrations V1–V15 apply on first start |
| `APP_PAYMENTS_PROOF_STORAGE_DIRECTORY` | Private volume for manual-payment proof files, outside any web root | Operator | refused (absolute path required) | Upload a proof, and check that it is on the volume and in the backups |
| Backups | Database and proof volume, together | Operator | not checked | Restore once, before launch |

### Payments
| Setting | What it is | Supplied by | If wrong | How to verify |
|---|---|---|---|---|
| `APP_PAYMENTS_ONLINE_RETURN_URL` | `https://<site>/orders/{orderId}/payment`, where the browser lands after the hosted page | Operator | refused (https only, not localhost) | Pay online once; the browser comes back to the site |
| `APP_PAYMENTS_ONLINE_API_BASE_URL` | Public https origin of the API | Operator | refused (https only, not localhost) | As above |
| `APP_PAYMENTS_SSLCOMMERZ_ENABLED`, `_STORE_ID`, `_STORE_PASSWORD`, `_IPN_URL`, `_FALLBACK_CUSTOMER_EMAIL` | SSLCommerz gateway (ADR-0012) | Business (merchant account), secret store | refused when enabled with values missing | One real low-value payment, then a refund |
| `APP_PAYMENTS_SSLCOMMERZ_BASE_URL` | **Defaults to the sandbox** | Operator | refused when the gateway is enabled with the sandbox or no address | Set it to the live SSLCommerz URL from the merchant panel |
| `app.payments.manual.accounts` | bKash / Nagad / bank receiving accounts shown at checkout (ADR-0022) | Business | warned if no accounts and no online gateway (cash on delivery only) | Checkout shows the right account numbers |

### SMS (ADR-0027)
| Setting | What it is | Supplied by | If wrong | How to verify |
|---|---|---|---|---|
| `APP_NOTIFICATIONS_SMS_SSL_WIRELESS_ENABLED=true`, `_API_TOKEN`, `_SENDER_ID` | SSL Wireless ISMS Plus | Business account, secret store | refused if not enabled, or the token or sender id is missing (before the database is touched); the https scheme and sender id length are checked by the adapter later in startup | One approved real test SMS |
| Outgoing IP allowlisting | The server's fixed egress IP is allowlisted in the ISMS Plus portal | Operator and SSL Wireless | not checked | The test SMS is delivered, not rejected |

## 2. People and access
- [ ] First ADMIN provisioned with the operator command (README, "Provisioning admins").
- [ ] A **second** ADMIN provisioned: whoever requests a refund can never approve it (ADR-0017).
- [ ] Operators know how to disable an account (`ADMIN_PROVISION_MODE=disable`, ADR-0029).
- [ ] Alerts on the `admin.*` and `account.*` audit events, and a periodic review of the ADMIN list.

## 3. Decisions still open
- [x] ADR-0023, 0024, 0025, 0028, 0029 and 0030 Accepted (2026-11-21).
- [ ] ADR-0018 (SSLCommerz): rerun the gateway contract suite against the sandbox, and decide the high-risk rule.
- [ ] ADR-0027 (SSL Wireless): get the provider's answers and send one approved real message, then accept.
- ADR-0026 (key-bound refresh tokens) stays future work.
- [ ] Real Safari (iOS) and Android Chrome checked: sign-in, refresh after a reload, checkout, payment return (QA-01 / ADR-0023).
- [ ] Delivery zones: fees set and activated (the seeded zones are inactive and have no fee).

## 4. Launch-day smoke test
1. The app starts, and the startup log shows no readiness warning, or only the one you expect.
2. `GET /actuator/health` returns UP.
3. Register a customer, sign in, and reload after 15 minutes: still signed in.
4. Place an order with each enabled payment method. The customer receives the SMS notices.
5. An ADMIN marks the order through to delivery. A second ADMIN approves a test refund, and the refund is recorded as sent.
