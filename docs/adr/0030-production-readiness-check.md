# ADR-0030: Refuse to start production on local-development defaults

- Status: Accepted 2026-11-21
- Date: 2026-11-21
- Related: ADR-0012 (payment gateways), ADR-0023 and ADR-0025 (refresh and the Origin allow-list), ADR-0027 (SMS sender required in production), ADR-0011 (payment proof storage)

## Context
- Some production requirements already fail fast under the `prod` profile:
  - no `SmsSender` (ADR-0027);
  - no persistent JWT signing key, unless `allow-ephemeral-key` is set;
  - the fake payment gateway and the fake SMS sender, which refuse `prod`.
- Several settings, however, ship with local-development defaults in `application.yaml` and were accepted silently in production:
  - `app.security.allowed-origins: [http://localhost:4200]`. Refresh and logout from the real site would be refused, signing everyone out every 15 minutes.
  - `app.payments.online.return-url` and `api-base-url` on localhost. Customers' browsers would be sent to localhost after paying.
  - `app.payments.proof-storage.directory: data/payment-proofs`, a relative path. Payment proofs would be written to the working directory, possibly not on a backed-up volume.
  - `app.payments.sslcommerz.base-url: https://sandbox.sslcommerz.com`. With the gateway enabled, real customers would pay in the sandbox.
  - `app.security.secure-cookies` and `app.security.jwt.allow-ephemeral-key` are each one flag away from an insecure production.
- Each of these would only show up in front of customers.

## Decision
- **A startup check, `ProductionReadinessCheck`,** active under `prod` and not under `provision-admin`. The operator command serves no web traffic, so it should not need the web settings.
- **It refuses to start**, with one message listing every problem, when:
  1. `app.security.allowed-origins` is empty, or lists any origin that is not `https://` or is local (`localhost`, `127.0.0.1`, `::1`, `0.0.0.0`);
  2. `app.security.secure-cookies` is `false`;
  3. `app.security.jwt.allow-ephemeral-key` is `true`;
  4. `app.payments.online.return-url` is missing, not `https://`, or local;
  5. `app.payments.online.api-base-url` is missing, not `https://`, or local;
  6. `app.payments.proof-storage.directory` is missing or not an absolute path;
  7. the SSLCommerz gateway is enabled while `app.payments.sslcommerz.base-url` is the sandbox (the shipped default) or unset. Real customers would otherwise pay in the sandbox, and orders could be confirmed without real money.
- **The message names each setting and its environment variable, never the value.** It points to `docs/launch-readiness.md`.
- **A warning only:** neither a manual receiving account nor an online gateway is configured, so customers can pay only by cash on delivery. That can be a deliberate business choice.

## Consequences
- A deployment that leaves a development default in place fails at startup with a precise message, instead of failing for customers.
- The checklist in `docs/launch-readiness.md` lists every production setting: what it does, who supplies it, and whether it is refused, warned about or not checked.
- Settings with no safe automatic rule stay on the checklist as manual checks, for example the SMS sender id and the outgoing IP allowlisting. A rule can be added later.

## Alternatives considered
- **Warnings only.** They are easy to miss in deploy logs, and the failures they predict are customer-facing.
- **A separate `application-prod.yaml` with production values.** That puts deployment-specific values (domains, paths) in the repository, and still does not catch a variable that was never set.
- **Checking at first use** (for example when a refresh arrives). That is too late: the site is already serving.

## Amendment (2026-11-22, QA finding F-4; the order was fixed by F-3, PR #38)
- **What runs before database access.** `ProductionReadinessCheck` is a `BeanFactoryPostProcessor`, so Spring runs it before creating any ordinary bean. A refusal from the seven rules in the Decision therefore happens before the application opens a database connection or runs a Flyway migration. This is covered by `ProductionStartupOrderIntegrationTests`: a refused configuration leaves a fresh database with no tables, and a safe configuration starts and migrates.
  - The check reads settings only. It does not test that a value works (for example that the domain resolves).
  - The cash-on-delivery-only warning is logged at the same point.
- **What still happens afterwards.** Other production refusals are ordinary bean checks and run when their bean is created, which can be after the migrations have run. Their order relative to Flyway is not guaranteed and not tested:
  - no `SmsSender` bean (ADR-0027);
  - no JWT signing key (`JwtConfig`), when `allow-ephemeral-key` is not set;
  - `FakeSmsSender` and `FakePaymentGateway`, which refuse the `prod` profile.
  - An unreachable or wrong database fails at connection time, as before.
- **Consequence.** A deployment can still apply migrations and then stop on one of these checks. `docs/launch-readiness.md` remains the list of what each setting needs.
- The operator command (`provision-admin`) is still excluded from the check.
