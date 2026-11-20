# Full application verification — 2026-11-19

Local end-to-end QA of `main` at `f96ca69` (after PR #19), run on one Windows 11 machine.
Every result below was executed in this pass; nothing is carried over from earlier runs unless it says so.

> **Status update, 2026-11-20.** The report below is kept as written on 2026-11-19 and describes `main` at `f96ca69`. Since then:
>
> | Finding | Status now |
> |---|---|
> | **QA-01** forced logout after an interrupted refresh | **Fixed in the web app** by PR #23 (`6cef240`, ADR-0023 rev 4). The grace window proposed in §8 was **rejected**. The server keeps strict rotation: a spent token always revokes the session. The browser now sends the refresh with `fetch(…, {keepalive: true})` and records it as in flight, so the next page waits for the rotated cookie. Verified in Chrome, Firefox and WebKit (Playwright builds). **Still open:** real Safari (iOS/macOS) and Android are untested, and a response lost in the network still signs the user out (ADR-0026, future work). |
> | **NEW-1** (found after this report) refresh/revocation race: a session could survive a logout or reuse detection, and concurrent reuse detections could deadlock | **Fixed** by PR #21 (`55f6c96`, ADR-0025 part 1): refresh, logout and reuse revocation serialize on a per-family advisory lock. |
> | Absolute session lifetime (related hardening) | **Added** by PR #22 (`7a7284b`, ADR-0025 part 2): customers 14 days idle / 30 days absolute, admins 8 hours / 12 hours. |
> | **QA-02** cash-collection banner shows before the payment line updates | Open. |
> | **QA-03** late payment on an expired order is unexplained; no reconciliation UI (N-06) | Open. |
> | **QA-04** no supported way to add a second admin | **Fixed** by PR #20 (`e572e5a`, ADR-0024): the one-off `provision-admin` command now also creates additional admins (`ADMIN_PROVISION_MODE=additional`), authorized by an existing enabled admin and audited. |
>
> ADR-0023, ADR-0024, ADR-0025 and ADR-0026 are still **Proposed**.
>
> **Still blocking launch:**
> - **No production SMS provider** (only the development fake exists, so the web app can't start in production).
> - The operations UI gaps N-01…N-06 (N-05 refunds and N-06 reconciliation in particular).
> - The production configuration in §12.3, other than the second admin.
> - A test against the real SSLCommerz sandbox.
> - Confirming that production serves the web app and the API from the same origin, which the QA-01 fix assumes.
>
> Phone numbers of the QA test accounts are blacked out in the screenshots.

**How each result was obtained** — every row in the matrices is tagged:

| Tag | Meaning |
|---|---|
| **Browser** | Driven in real Google Chrome (installed channel, headless) through Playwright `playwright-core` 1.49.1 against the Angular dev server. Clicks, typing, file upload, screenshots. |
| **Browser + DB** | As above, and the outcome was also checked with SQL in the throwaway test database. |
| **API** | HTTP calls with `fetch` from Node, no browser. Used where no UI exists, or for tampering the UI cannot do. |
| **Unit/Integration** | The automated test suites (`./mvnw verify`, `npm run test:ci`). |

## 1. Environment

| Item | Version / value |
|---|---|
| OS | Windows 11 Pro 10.0.26200, Git Bash + PowerShell 5.1 |
| Java | 25.0.2; Maven wrapper 3.10.1; Spring Boot 4.1.2 |
| Database | PostgreSQL 17.12-alpine (Testcontainers, throwaway), Flyway V1–V12 |
| Node / npm | 24.22.3 / 11.19.1 |
| Angular / TypeScript / Tailwind / Vitest | 22.2.6 / 6.0.3 / 4.3.5 / 5.1.0 |
| Docker | Engine 29.5.3, Compose v5.1.4 |
| Browser | Google Chrome (installed), driven by playwright-core 1.49.1 installed outside the repo |

### Startup (Phase B)

| Service | URL | Command | Result |
|---|---|---|---|
| API (profile `dev`) | http://localhost:8080 | `cd backend && ./mvnw spring-boot:test-run -Dspring-boot.run.arguments="--spring.profiles.active=dev"` | Started; `GET /actuator/health` → `{"status":"UP"}` |
| Web | http://localhost:4200 | `cd frontend && npm start` (proxies `/api` → :8080) | Started |
| Fake payment gateway page | http://localhost:4200/dev/fake-gateway | `dev` profile only | Works |
| Database | random port, Testcontainers | started by `test-run` | Throwaway; removed when the JVM exits |

- No existing data or Docker volume was touched. A native PostgreSQL already listening on 5432 and one existing Docker volume were left alone; the app's database is a separate Testcontainers container.
- One API start took ~24 minutes instead of ~30 s because the machine's CPU was saturated by other Chrome processes on the machine (not this test). Normal starts took under a minute.

### QA-only configuration (environment variables on the API process; no file changed)

| Override | Why |
|---|---|
| `APP_RATE_LIMITS_POLICIES_{LOGIN,REGISTER,CHECKOUT,MANUAL_PAYMENT,PAYMENT_INITIATION}_LIMIT=1000`, `..._REFRESH_LIMIT=5000` | The first run hit the per-IP limits (login 10/5 min, register 5/15 min…) and every later test got `429`. That is the limiter working, and it is listed as a PASS below. Raised only so one IP could run ~50 scenarios. |
| `APP_PAYMENTS_MANUAL_ACCOUNTS_0_*` = BKASH, "bKash (QA test account)", "QA Test - not a real account", 01700000000 | MANUAL payment is offered only when a receiving account is configured; the default is none. A clearly fake account was used. |
| `APP_PAYMENTS_PROOF_STORAGE_DIRECTORY` = a scratch folder | Uploaded proof images stayed out of the repo. |

**Configuration that is missing for a real deployment (reported, not invented):** a real SSLCommerz store id/password, real manual receiving accounts, an SMS provider, a persistent JWT signing key, and a production database. None were used.

**Test data:** created through the real admin APIs (`qa-setup.mjs`, **API**). Two admin accounts were registered normally, then promoted with `UPDATE users SET role='ADMIN'` **in the throwaway database only**, because the supported command (`provision-admin` profile, README "Provisioning the first admin") creates only the first admin and the test needed two (see **QA-04**). That command itself was not run in this pass.

## 2. Feature inventory (Phase A)

Built from the code (`app.routes.ts`, feature components, controllers), not from the docs.

### Customer web app — implemented

| Route | Feature |
|---|---|
| `/` | Catalog: active products, category filter, search, empty state |
| `/products/:slug` | Product details; cake customization (single/multiple/text groups, price deltas, disallowed pairs, lead-time notice) |
| `/cart` | Cart (stored in the browser), quantities, remove, header count |
| `/checkout` | Sign-in guard, address/zone/date/slot, server-side quote, COD / MANUAL / ONLINE payment choice, idempotent placement |
| `/orders`, `/orders/:id` | Order history and details; customer cancel (refund request for paid orders) |
| `/orders/:id/payment` | ONLINE payment start / retry; MANUAL reference + proof upload |
| `/login`, `/register`, `/account` | Auth with in-memory access token and rotating refresh cookie |
| `/dev/fake-gateway` | Development payment simulator (dev profile only) |
| `**` | Not-found page |

### Admin web app — implemented

| Route | Feature |
|---|---|
| `/admin` | Dashboard (Dhaka "today" figures, work queues) |
| `/admin/orders` | Order list with status filter |
| `/admin/orders/:id` | Fulfilment status steps, cash collection (COD), cancel with reason, refunds (read-only) |
| `/admin/payments` | Manual payment verification: view proof, approve, reject |

### NOT IMPLEMENTED in the UI (backend API exists)

The backend exposes 66 endpoints. These admin areas have **no Angular route or screen**; they were exercised only through the API:

| ID | Area |
|---|---|
| N-01 | Product & category management |
| N-02 | Customization groups/options, disallowed combinations |
| N-03 | Inventory (ingredients, stock adjustments) |
| N-04 | Delivery zones & slots |
| N-05 | Refund request / approval / completion |
| N-06 | Payment reconciliation-case resolution |

Also not implemented anywhere: real SMS sending (notifications are only logged), and any way to add a second admin (see **QA-04**).

## 3. Customer browser tests (Phase C)

Final status after retests (see §9). Screenshots: `docs/qa/screenshots/` (selected) and the full set listed in §11.

| ID | Scenario | How | Result | Evidence |
|---|---|---|---|---|
| C-01 | Home lists only ACTIVE products with BDT prices | Browser | PASS | 4 products; DRAFT not shown. `screenshots/C-01-home.png` |
| C-02 | Category filter | Browser | PASS | Custom Cakes → 1, All → 4 |
| C-03 | Search + empty state | Browser | PASS | "walnut" → 1; nonsense → empty state |
| C-04 | Product page; DRAFT and unknown slug show "not available" | Browser | PASS | API answers 404 for both |
| C-05 | Cake customization: required groups, price deltas, disallowed Vanilla+Walnuts, 72 h topping notice, 50-char message | Browser | PASS | Walnuts disabled after Vanilla; 55 chars refused. `screenshots/C-05-cake-customized.png` |
| C-06 | Cart add / update / remove, survives reload, header count | Browser | PASS | "Cart 4" |
| C-07 | Checkout needs sign-in | Browser | PASS | → `/login?returnUrl=%2Fcheckout` |
| C-08 | Registration validation | Browser | PASS | BD mobile format, password mismatch |
| C-09 | Register; signed in; cart kept | Browser | PASS | |
| C-10 | Session survives reload (refresh cookie) | Browser | PASS | but see **QA-01** |
| C-11 | Checkout form validation | Browser | PASS | name / area / address messages |
| C-12 | Lead time enforced by the server (cake 48 h, day+2 slot) | Browser | PASS | "needs at least 48 hours' notice" (422 from `/checkout/quote`) |
| C-13 | Server quote with customization + fee; COD order | Browser + DB | PASS | ৳6,660.00 = cake 2,850 + 3× fudge 3,750 + ৳60; order Confirmed; cake message markup rendered as text. `screenshots/C-13-quote.png` |
| C-14 | Cart emptied after placing | Browser | PASS | |
| C-15 | Zone minimum (Mirpur ৳3,000) | Browser | PASS | "The minimum order for Mirpur is BDT 3,000.00." `screenshots/C-15-zone-minimum.png` |
| C-16 | Inactive zones not offered | Browser | PASS | only Dhanmondi, Mirpur |
| C-17 | MANUAL: account shown; reference + proof submitted; payment stays pending | Browser + DB | PASS (retest) | UI "Waiting for payment" → "Checking your payment"; DB `PENDING_PAYMENT/PENDING` |
| C-18 | MANUAL second order (for rejection) | Browser | PASS | |
| C-19 | ONLINE success through the dev simulator | Browser + DB | PASS | Confirmed, "Paid online." `screenshots/C-19b-paid.png` |
| C-20 | ONLINE duplicate: reopening a finished gateway session | Browser + DB | PASS | exactly 1 SUCCEEDED payment |
| C-21 | ONLINE failure, retry, pay | Browser | PASS | |
| C-22 | Hold expiry → EXPIRED, holds released; late payment does not confirm | Browser + DB | PASS | slot reserved 11→10; late pay → order stays EXPIRED, case `LATE_SUCCESS:OPEN`. `screenshots/C-22b-late-payment.png` (see **QA-03**) |
| C-23 | Last unit of stock (Gold leaf = 1) | Browser | PASS | qty 2 refused; 1 succeeds; next refused: "not available in that quantity" |
| C-24 | Slot capacity 1: one order, then slot no longer offered | Browser + DB | PASS (retest) | 1/1 reserved; 2 pm slot gone. `screenshots/C-24-retest-slot-full.png` |
| C-25 | Order history with statuses | Browser | PASS | 7 orders |
| C-26 | Customer cancels a paid ONLINE order → refund requested | Browser | PASS | "We are processing a refund…" |
| C-27 | Another customer's order id | Browser | PASS | "We could not find this order" (no enumeration). `screenshots/C-27-foreign-order.png` |
| C-28 | Customer opens `/admin` | Browser | PASS | redirected to `/` |
| C-29 | Logout; protected page then goes to login | Browser | PASS | |
| C-30 | Wrong password, then correct login | Browser | PASS | "The phone, email or password is incorrect." |
| C-31 | Duplicate registration | Browser | PASS | "An account with this phone number or email already exists." |

## 4. Admin browser tests (Phase C)

| ID | Scenario | How | Result | Evidence |
|---|---|---|---|---|
| A-01 | Admin login → dashboard | Browser | PASS | Today figures in ৳, work queues. `screenshots/A-01-dashboard.png` |
| A-02 | Orders list + status filter | Browser | PASS | all 8; PENDING_PAYMENT 2 |
| A-03 | COD order Confirmed → Preparing → Ready → Out for delivery → Delivered; cash collected; stock consumed once at PREPARING | Browser + DB | PASS (DB) | Status steps worked in the UI. DB: one CONSUMPTION movement per order line, at PREPARING only; payment SUCCEEDED after cash collection. The page's payment line lagged — **QA-02** |
| A-04 | Manual verification: view proof, approve one, reject another | Browser + DB | PASS | approved → CONFIRMED/SUCCEEDED; rejected → CANCELLED/FAILED. `screenshots/A-04a-proof.png` |
| A-05 | Customer then sees the verified order as Confirmed | Browser | PASS | |
| A-06 | Admin cancels with a reason; slot capacity released | Browser + DB | PASS (retest) | slot reserved 1→0; history `ADMIN: QA: customer phoned to cancel`. `screenshots/A-06-retest-cancelled.png` |
| A-07 | Refund opened by customer cancellation visible on admin order page | Browser | PASS | ৳1,310.00 · REQUESTED |
| N-01…N-06 | Admin catalog, customization, inventory, slots/zones, refund actions, reconciliation | — | **NOT IMPLEMENTED** | no UI (see §2) |

## 5. Payments, refunds and integrity (API, where no UI exists)

| ID | Scenario | How | Result | Evidence |
|---|---|---|---|---|
| R-01 | Full refund: other admin approves, then completes → payment REFUNDED | API + DB | PASS | refund ৳1,310 |
| R-02 | Partial refund ৳500; requester approves own → `409 SECOND_APPROVER_REQUIRED`; second admin → 200; over-limit → `409 REFUND_EXCEEDS_REMAINING` | API + DB | PASS | payment SUCCEEDED, refunded 500.00 |
| R-03 | Forged client price/fee fields ignored; idempotent replay; key reused for different cart | API | PASS | total 1,460 (server price); same order returned; `422 IDEMPOTENCY_KEY_REUSED` |
| S-01 | RBAC: customer → admin API `403`; anonymous `401` | API | PASS | |
| S-02 | Zone activation without fee; negative stock; duplicate slug; slot ending before start | API | PASS | 422 / 422 / 409 / 422 |
| S-03 | DRAFT product hidden from public catalog | API | PASS | |
| S-04 | Rate limiting on auth endpoints | Browser (first run) | PASS | 429s on login/refresh before limits were raised |

**Payment scope:** only the development payment simulator (`/dev/fake-gateway`) was used. No real payment, refund or SMS was sent. SSLCommerz was not contacted.

## 6. Backend and frontend automated tests (Phase D)

| Backend command | Result |
|---|---|
| `cd backend && ./mvnw -B verify` (run after stopping the app servers) | **PASS** — BUILD SUCCESS, 68 test classes, **506 tests, 0 failures, 0 errors, 0 skipped** (Surefire reports) |

The one `ERROR … Unhandled exception … secret_admin` line in the build log comes from `ApiExceptionHandlerTest`, which deliberately throws it to prove that internal details are not returned to clients.

| Frontend command | Result |
|---|---|
| `npm run api:check` | PASS — generated client matches `docs/api-contract.yaml` |
| `npm run format:check` | PASS |
| `npm run lint` | PASS — all files pass |
| `npm run test:ci` | PASS — 20 files, 126 tests |
| `npm run build` | PASS |

Requested areas and the backend integration tests that cover them (Testcontainers PostgreSQL):

| Area | Tests (examples) |
|---|---|
| Concurrency: last stock / last slot / duplicate submit | `OrderConcurrencyIntegrationTests`: `onlyOneCustomerGetsTheLastStock`, `onlyOneCustomerGetsTheLastSlotCapacity`, `concurrentDuplicatesOfOneRequestCreateExactlyOneOrder` |
| Transaction rollback | `OrderPlacementIntegrationTests`: `losingTheStockRaceRollsBackEverything`, `losingTheCapacityRaceRollsBackEverything`, `validationFailuresWriteNothing`, `reusingAKeyForADifferentOrderIsRejected` |
| Payments | `OnlinePaymentIntegrationTests`: `aVerifiedSuccessConfirmsTheOrderExactlyOnce`, late success after expiry |
| Refunds (25 tests) | `RefundWorkflowIntegrationTests`: `theRequesterCannotApproveTheirOwnRefund`, … |
| Hold expiry | `HoldExpiryIntegrationTests` |
| Access control | `AccessControlIntegrationTests` |
| Refresh token rotation under concurrency | `RefreshConcurrencyIntegrationTests` |
| Migrations | `DatabaseMigrationTests` (7) |

Concurrency races were **not** reproduced in the browser; they are covered only by these integration tests.

## 7. UI/UX, mobile, accessibility, BDT, Dhaka time (Phase E)

| ID | Check | How | Result | Evidence |
|---|---|---|---|---|
| E-01 | Dhaka times with the browser set to America/New_York | Browser | PASS | slot shown as 10:00 am – 12:00 pm (Dhaka). `screenshots/E-01-new-york-browser.png` |
| E-02 | Mobile 390×844: 8 customer pages, no horizontal scroll | Browser | PASS | `screenshots/E-02-mobile-checkout.png` |
| E-03 | Mobile: 4 admin pages | Browser | PASS | |
| E-04 | Accessibility basics on 14 pages (lang, one h1, form labels, accessible names, alt text, broken images) | Browser (DOM checks, not an audit tool) | PASS | no problems found |
| E-05 | Unknown route → not-found page | Browser | PASS | |
| — | BDT formatting | Browser | PASS | `৳1,310.00` style everywhere; server messages use `BDT 3,000.00` |

E-04 is a scripted DOM check, not a full WCAG audit (no axe, no screen reader, no colour-contrast measurement).

## 8. Bugs and findings

### QA-01 — Interrupted session refresh signs the user out permanently (Medium)

- **Steps:** sign in; reload any page; within ~150 ms navigate again (or the tab is closed / network drops while `POST /api/v1/auth/refresh` is in flight).
- **Observed:** the next refresh returns `401`; the user is signed out of every tab. API log: `Refresh token reuse detected: revoked family … (2 active tokens)`.
- **Expected:** a cancelled request should not end the session.
- **Root cause:** `AuthService.refresh` (`backend/.../identity/service/AuthService.java`) rotates the token and commits even when the browser has already aborted the response, so the new `Set-Cookie` is never stored. The next request presents the old (now used) token, which the reuse detector correctly treats as theft and revokes the whole family.
- **Reproduced:** in real Chrome twice (`qa-probe.mjs`, `qa-probe2.mjs`): reload, second navigation after 150 ms → aborted refresh → `401`.
- **Impact:** forced logouts on flaky mobile networks; carts are kept (browser storage), so no data loss.
- **Status:** **not fixed** — security-sensitive auth design. Proposed plan (needs approval):
  1. Give the previous token a short **grace window** (e.g. 10–30 s): if a *used* token is presented within the window **and** its successor has never been used, return that successor's session again (re-issue the same successor cookie or rotate the successor), instead of revoking.
  2. Outside the window, or when the successor has already been used, keep today's behaviour (revoke family).
  3. Add integration tests: replay within window → same family continues; replay after window → revoked; replay after successor used → revoked; concurrent replays.
  4. Update `docs/business-rules.md`/ADR on refresh reuse and the threat-model note.

### QA-02 — Admin order page shows the cash-collection banner before the payment line updates (Low)

- **Steps:** admin → COD order Delivered → enter note → **Record cash collected**.
- **Observed:** "Cash collection recorded." appears while the payment line still reads `COD · PENDING`; it updates after the panel reloads. DB is correct (`SUCCEEDED`).
- **Root cause (likely):** success message set before the order/payment reload completes. Not investigated further.

### QA-03 — Expired order that was paid late gives the customer no explanation (Low, UX)

- **Observed:** order page shows **Expired** and **Paid online.** with no message that the payment is being reviewed / refunded (`screenshots/C-22b-late-payment.png`). Backend correctly opened a `LATE_SUCCESS` case.
- **Related gap:** an admin cannot resolve that case in the UI (N-06); the dashboard shows "Reconciliation 1" with no link. Resolution is API-only today.

### QA-04 — No supported way to add a second admin, but admin-requested refunds need one (Medium)

- **What exists:** the `provision-admin` profile (`AdminProvisioningRunner`, README "Provisioning the first admin") creates the **first** ADMIN and refuses to run once any ADMIN exists. Public registration only creates customers. There is no API or screen to change a user's role.
- **Rule:** "Whoever requested a refund never approves it: a second, enabled ADMIN must" (business-rules.md, ADR-0017). `RefundWorkflow.decide` returns `409 SECOND_APPROVER_REQUIRED` (seen in R-02).
- **Impact:** a store set up only through supported means has one admin, so any refund that admin requests (for example, resolving a late payment, N-06) can never be approved. Refunds opened by a customer cancelling are not affected (R-01: approved by an admin who did not request it).
- **How found:** code and docs review, plus R-02. This test promoted the second admin with SQL in the throwaway database, which is not a supported path.
- **Status:** not fixed. Needs a product decision: allow provisioning additional admins (for example, an `--allow-additional` flag on the same one-off command, or an admin-only user-role endpoint with audit).

### Test-script errors (not app bugs)

The first runs reported failures that were caused by the QA script, then fixed and retested in the browser: wrong selectors (`#password-error`, hidden `#provider` with one account, slot labels that start with the date), reading status one step early (A-03), a two-step cancel confirmation (A-06), waiting for Angular re-renders, wrong table names in SQL. C-17 originally expected a different status label; "Checking your payment" is correct.

Aborted requests seen in the logs during C-17/C-18 (`ERR_ABORTED` on `manual-submission` and `POST /orders`) were navigation cancellations after the server had responded: the DB shows exactly one submission per MANUAL order and no duplicate orders.

## 9. Fixes and retests (Phase F)

No application code was changed. No small, isolated fix was identified that is safe to make without design review: QA-01 is auth-security design (plan above, awaiting approval); QA-02 and QA-03 are minor UX and can be fixed later on a `bugfix/` branch from `main` if wanted.

Retests (Browser + DB): C-17 PASS, C-24 PASS, A-06 PASS, A-03 PASS on DB evidence (UI lag is QA-02).

## 10. Limitations

- Real SSLCommerz, real bKash/Nagad accounts and SMS were not tested (no credentials; not allowed).
- Lost gateway callback → scheduled reconciliation was not run in this pass; it is covered only by backend integration tests here.
- Concurrency races only via integration tests, not via parallel browsers.
- One machine, one browser (Chrome, headless). No Safari/Firefox, no real phone.
- Rate limits were raised for the run; behaviour at the real limits was only seen incidentally (429s).
- Admins were promoted in SQL in the throwaway database; the supported `provision-admin` command was not run in this pass (it has its own integration test, `AdminProvisioningIntegrationTests`).
- Accessibility check is basic (§7).

## 11. Logs and artifacts (local, not committed)

Scratch folder `%TEMP%\claude\…\scratchpad\`: `qa\api*.log`, `qa\web.log`, `qa\run2–5.log`, `qa\shots\*.png` (56 screenshots), `driver\qa-*.mjs` (setup, browser suite, retests, probes), `driver\qa-results.json`, `driver\qa-retest.json`. Thirteen screenshots are copied to `docs/qa/screenshots/`.

## 12. Release-readiness assessment

**Customer ordering and admin fulfilment: functionally ready for a controlled pilot. Not ready for production launch yet.**

Good:

- Every implemented customer and admin flow passed in a real browser, with database checks on money, stock, slots and payment state.
- Server-side pricing, lead time, zone minimums, stock and slot limits, idempotency, RBAC and two-person refunds all held under direct API tampering.
- Backend and frontend suites are green.

Blocking or needed before launch:

1. **Operations UI gaps (N-01…N-06):** catalog, stock, slots, refunds and reconciliation need API calls today. In particular, a customer who paid late (QA-03) cannot be refunded or reinstated without API access.
2. **QA-01** forced logouts: fix with a grace window (plan above).
3. **Production configuration** not yet provided or tested: SSLCommerz credentials and callback URL, manual receiving accounts, SMS provider, persistent JWT key, a way to add a second admin (QA-04), cookie/domain settings behind HTTPS.
4. Run a test against the real SSLCommerz sandbox before taking money.
