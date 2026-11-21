# ADR-0024: Provision additional admins through the operator command

- Status: Accepted 2026-11-21 (revision 3: password sources restricted, web-server guard, secret-file requirements; with the 2026-11-21 amendment below)
- Date: 2026-11-19
- Builds on: ADR-0013 (first ADMIN by an operator-run command), ADR-0016 (SMS notices), ADR-0017 (second-approver refund review)

## Context
QA-04 (docs/qa/full-application-verification.md) found:
- The `provision-admin` command creates only the **first** ADMIN and refuses once any ADMIN exists.
- Public registration creates only customers, and there is no API or screen to change a role.
- The refund rule says the ADMIN who requested a refund never approves it; another enabled ADMIN must (`SECOND_APPROVER_REQUIRED` in `RefundWorkflow.decide`, plus the `ck_refund_second_approver` CHECK).

So a store set up only through supported means has one admin, and an admin-requested refund can never be approved. The QA run worked around this with SQL in a throwaway database, which is not a supported path.

Revision 1 also found that the command could not start outside the dev/test profiles:
- It loads the full application context.
- `OutboxDispatcher` takes every `OutboxHandler`; the six order-notice handlers need `OrderNotificationDelivery`, which needs an `SmsSender`.
- The only `SmsSender` is the dev/test `FakeSmsSender`.

## Decision

### Command-based provisioning
The existing command gets a second, explicit mode, `ADMIN_PROVISION_MODE=additional`. The default, `first`, behaves exactly as before.
- **No REST endpoint** for creating admins or assigning roles.
- **No conversion:** the target must be a new account. A phone or email already in use is refused, including a customer's (email compared without regard to case).
- The same name and password rules as the first ADMIN apply (12–128 characters).

### Separation from notification infrastructure
Under the `provision-admin` profile these beans are left out (`@Profile("!provision-admin")`):
- `OrderNotificationDelivery`
- `OrderNoticeHandlers`
- `OutboxDispatcher`
- `OutboxDispatchJob`

The command sends no notices, so it needs no `SmsSender`. No sender is activated, the fake one included.

**Can provisioning mode disable notifications in normal web startup?** No:
- The exclusions apply only when the `provision-admin` profile is active, and that profile turns the web server off.
- If someone forces a web server on anyway (`spring.main.web-application-type=servlet`), the command refuses to run and startup fails. The profile can never serve traffic without notification delivery.
- Normal web startup (without the profile) keeps every notification bean, and still requires an `SmsSender`.

The profile also sets `web-application-type: none` and `app.scheduling.enabled: false`, so the command starts:
- no web server;
- no `@Scheduled` jobs (outbox dispatch, hold expiry, reconciliation, token cleanup);
- no outbox dispatcher.

Database, Flyway, JWT key policy, password hashing and audit stay as in production.

**Normal web startup still requires notification configuration.** The web application publishes order and refund events that must reach customers. Starting it without an `SmsSender` would silently drop notices, so it keeps failing at startup (`NoSuchBeanDefinitionException: SmsSender`) until a provider is configured. Only the command, which publishes nothing, is exempt.

### Authentication and authorization
- An additional ADMIN is authorized by an existing ADMIN, identified by `PROVISIONER_IDENTIFIER` (phone in +880 form, or email) and `PROVISIONER_PASSWORD`.
- The backend verifies, against the database:
  - the account exists;
  - the Argon2 password matches;
  - the role is ADMIN;
  - the account is enabled.
- **No enumeration:** the password is checked before role and status. An unknown account still costs one Argon2 verification against a dummy hash. Every provisioner failure gives the operator the same message ("The provisioner could not be authenticated as an enabled ADMIN."). The specific reason goes only to the audit trail.
- `additional` mode cannot bootstrap: with no ADMIN, there is no valid provisioner.
- **An admin password alone is not enough.** Running the command also requires operational access to the production environment: the production database credentials and the JWT key secret, in a context where the application can run. See "Operational access" below.

### Concurrency and uniqueness
- Both modes take the same transaction-scoped advisory lock, so runs are serialized.
- The unique phone and email indexes are the backstop. Only a unique violation (SQLSTATE 23505) is reported as `ACCOUNT_EXISTS`; any other database error is an operational failure.

### Audit integrity
Every attempt produces **exactly one** record in the existing append-only `audit_events` table. The table already has `actor_type`, `actor_user_id`, `action`, `entity_id`, `reason`, JSON `details` and `created_at`, so no migration is needed.

| Outcome | Action | Written | Actor |
|---|---|---|---|
| Created | `admin.provisioned` (entity = new admin) | **in the same transaction as the account** | `SYSTEM` (first) / `ADMIN` = provisioner |
| Refused (validation, state, authorization) | `admin.provisioning_refused`, `reason` = code | in its own transaction, after the attempt rolled back | the provisioner if authorized, else `SYSTEM` + `claimedProvisionerId` |
| Failed (database or audit error) | `admin.provisioning_failed`, `reason` = `PROVISIONING_FAILED` | in its own transaction, after the attempt rolled back | as above |

- **If the success record cannot be written, the account is rolled back.** The code writes it inside the account's transaction, and PostgreSQL in any case aborts a transaction whose statement failed. The operator gets `ProvisioningFailed`, "no administrator was created".
- **If the refusal or failure record cannot be written either,** the operator gets `ProvisioningAuditFailure` ("could not be recorded in audit_events; nothing was created"), which never reports success. The fallback is the application log, with the mode, the reason code and the exception type only.
- **No duplicate or contradictory records.** A success is recorded only by the transaction that created the account. The refusal/failure path runs only when that transaction did not commit. A retry is a new attempt with its own record (for example, `ACCOUNT_EXISTS` after an earlier success).
- **Details** are `mode`, `outcome`, `provisionedBy`, the target phone masked to its last 3 digits, whether an email was given, and `processAccount`. The timestamp is `created_at`.
- **Never stored:** passwords, hashes, tokens, or the full target phone or email.
- **Operational identity.** `processAccount` is the OS account the JVM reports (`user.name`). It is recorded as exactly that, **not** as a verified human operator, and no other operator identity is invented. The authenticated provisioner is the verified identity.
- **Tamper resistance.** The `trg_audit_events_append_only` trigger rejects UPDATE and DELETE, and no customer or admin API reads or writes `audit_events`; both are tested.

### Secret handling
- **Where passwords come from.** Passwords are read **only** from a secret file named by `ADMIN_PASSWORD_FILE` / `PROVISIONER_PASSWORD_FILE` (preferred) or from the **process environment**.
- **Where they are refused.** A password found in any other configuration source fails the command, and the error names the source, never the value. That covers:
  - command-line arguments (`--ADMIN_PASSWORD=…`);
  - JVM system properties (`-DPROVISIONER_PASSWORD=…`);
  - `spring.application.json`;
  - configuration files.

  Process listings, shell history or the repository would otherwise expose them.
- **Never logged or echoed.** Error messages carry no secret values, and a file read error reports only the exception type, not the path's contents.
- **Secret-file requirements** (operator duty; the command cannot verify permissions portably, for example on Windows hosts):
  - delivered by the platform's secret mechanism (orchestrator secret, mounted secret volume or tmpfs), never written to an image, a repository or a shared disk;
  - owned by the account that runs the command and readable only by it: mode `0400` (or `0600`), no group or world access;
  - one password per file; a single trailing line break is ignored;
  - removed, or the secret unmounted, as soon as the run finishes, and rotated if it may have been exposed.

### Refund separation of duties
Unchanged: `SECOND_APPROVER_REQUIRED` and `ck_refund_second_approver` are untouched. A test covers the whole workflow:
1. Admin A is created in `first` mode; A provisions admin B in `additional` mode.
2. A requests a refund.
3. A's own approval gets `SECOND_APPROVER_REQUIRED`, and a direct SQL self-approval is refused by the CHECK.
4. B approves, and the refund completes.
5. Requester and approver differ in `refunds` and in `audit_events`.

## Operational access (production)
- **Who may run the command:** only named operators on the deployment team, through the deployment platform's one-off task or `exec` mechanism, for example a one-off container from the release image. Not from developer laptops, and not from CI jobs that print their environment.
- **Least privilege:**
  - Production hosts and containers: shell or exec access limited to that operator group and logged by the platform.
  - Production database credentials: held in the secret store, readable only by the application runtime and that group.
  - Deployment credentials (registry, orchestrator): separate from application credentials, with no standing access for developers.
  - The provisioner's password: known only to that ADMIN, never shared with the operator unless the operator *is* that ADMIN.
- **Running it:** inject `ADMIN_PASSWORD_FILE` / `PROVISIONER_PASSWORD_FILE` from the secret store. Never type passwords inline (shell history) or pass them as arguments. Unset or remove the secret after the run.
- **Ongoing controls:**
  - Alert on every `admin.provisioned`, `admin.provisioning_refused` and `admin.provisioning_failed` event.
  - Review the ADMIN list and their `admin.provisioned` records monthly.
  - Rotate admin passwords when people change roles or leave.
  - Rotate database credentials after operator changes.

## Alternatives considered
- **An admin-only REST endpoint to create admins or assign roles.** It adds an online attack surface for privilege escalation, plus a UI, CSRF and session concerns. Rejected for the MVP.
- **Promoting an existing customer account.** Invites escalation through a compromised customer account. Rejected; admins are created fresh.
- **Supported manual SQL.** No audit, no validation. Rejected.
- **A flag that lets `first` mode skip its "no ADMIN yet" check.** No authorization from an existing admin. Rejected.
- **A no-op or fake `SmsSender` in command mode.** It would put a sender that silently discards messages into a production-profile context. Rejected in favour of leaving notification delivery out.
- **A separate minimal Spring context for the command.** It cleanly avoids unrelated beans, but means more configuration to maintain. Deferred; the profile exclusions are the smaller change.
- **MFA or an external identity provider for provisioning.** Out of scope without approval; noted as the next step if the threat model requires it.

## Residual risks
- **Operator with server access plus an admin password.** One person with production access and an ADMIN password (their own, or one obtained from another admin) can create a second ADMIN they control, then approve their own refunds through it. No control in this design prevents that. Separation of duties then rests on:
  - organisational control of server access and admin credentials;
  - monitoring: every admin's `admin.provisioned` record names the authorizing admin, and refunds record requester and approver.
- **`processAccount` is not a human identity.** In containers it is usually a service account. Attributing a run to a person relies on the platform's own access logs.
- **Environment variables** can be read by root or by the same OS user through `/proc`. Secret files with the permissions above are preferred. The command does not check file permissions, so that remains an operator control.
- **No way to disable or remove an admin** without SQL yet. A candidate for the same command.
- **Production web startup** still needs a real SMS provider. That is a launch blocker outside this ADR.

## Verification (2026-11-19, branch `bugfix/qa-04`)
All counts are from actual runs.
- **`AdminProvisioningIntegrationTests`: 18 tests.** The 2 original tests are unchanged. The others cover:
  - each success writing exactly one matching record;
  - **a success-audit outage rolling the admin back** (simulated with a temporary trigger in the isolated test database), recorded as `admin.provisioning_failed`;
  - **a refusal-audit outage giving `ProvisioningAuditFailure`**, with nothing created;
  - unknown, wrong-password, customer and disabled provisioners refused and audited with the authorizing or claimed identity;
  - the same message for unknown, wrong-password and customer provisioners;
  - an existing customer never converted;
  - repeated attempts: one admin, one success record, then `ACCOUNT_EXISTS` refusals;
  - 4 concurrent runs: exactly one admin and one success record naming it;
  - append-only audit, and no API path for audit, roles or provisioning;
  - no secrets or full contact details in the audit.
- **`AdminProvisioningCommandIntegrationTests`: 6 tests** (revision 3), running the real command with **only** `provision-admin` (no `test` or `dev` profile):
  - both modes, the second using secret files: no `SmsSender` or `FakeSmsSender` bean, no notification delivery or outbox dispatcher, no scheduling, no web server, and no passwords in the captured output;
  - refusals fail the command with an identical message for an unknown account and a wrong password;
  - passwords in a command-line argument, a `-D` system property or `spring.application.json` are refused, each with an error naming the source and not the value;
  - the command refuses to run when a web server is forced on;
  - the JWT key policy is still enforced;
  - **normal web startup without an SMS provider still fails** (`NoSuchBeanDefinitionException: SmsSender`).
- **`ProvisionedAdminsRefundIntegrationTests`:** the full A/B workflow, including the database refusing a direct self-approval.
- **Existing tests unchanged and passing:** `RefundWorkflowIntegrationTests` (25), `OrderNotificationIntegrationTests` (9), `OutboxIntegrationTests` (7).
- **Mutation checks** (revision 2):
  - With the command-line secret check disabled, 1 test failed (`passwordsOnTheCommandLineAreRefused`, since replaced by `passwordsOutsideTheProcessEnvironmentAreRefused`).
  - With the success record moved after the commit, 1 test failed (`aFailedSuccessAuditRollsBackTheNewAdmin`).
  - With the audit-write error swallowed in code, no test failed: PostgreSQL aborts the failed transaction regardless, so the admin is still rolled back.
- **Full build (revision 3):** `./mvnw -B clean verify` ran 529 tests, 0 failures, 0 errors, 0 skipped. The spec check passes. An earlier run of the same build failed before any test ran: the Testcontainers PostgreSQL did not become ready in time. An unchanged rerun passed.

## Amendment (2026-11-21, on acceptance)
- Disabling and re-enabling accounts were added to the same command (ADR-0029, PR #32). That resolves the residual risk "No way to disable or remove an admin without SQL yet".
- The SMS adapter exists (ADR-0027, PR #28). Production startup now waits only on the SSL Wireless account.
