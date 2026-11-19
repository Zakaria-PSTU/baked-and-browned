# ADR-0024: Provision additional admins through the operator command

- Status: Proposed
- Date: 2026-11-19
- Builds on: ADR-0013 (first ADMIN by an operator-run command), ADR-0017 (second-approver refund review)

## Context
QA-04 (docs/qa/full-application-verification.md) found:
- The `provision-admin` command creates only the **first** ADMIN and refuses once any ADMIN exists.
- Public registration creates only customers, and there is no API or screen to change a role.
- The refund rule says the ADMIN who requested a refund never approves it; another enabled ADMIN must (`SECOND_APPROVER_REQUIRED`, enforced both in `RefundWorkflow.decide` and by the database).

So a store set up only through supported means has one admin, and any refund that admin requests can never be approved. The QA run worked around this with SQL in a throwaway database, which is not a supported path.

## Decision
Extend the existing command with a second, explicit mode, `ADMIN_PROVISION_MODE=additional`. The default, `first`, behaves exactly as before.

**Authentication and authorization**
- The new ADMIN is authorized by an **existing ADMIN**, identified by `PROVISIONER_IDENTIFIER` (phone in +880 form, or email) and `PROVISIONER_PASSWORD`.
- The backend verifies the provisioner against the database:
  - the account exists;
  - the Argon2 password matches (checked before role and status, so a wrong password reveals neither);
  - the role is ADMIN;
  - the account is enabled.
- Unknown, wrong-password, non-admin and disabled provisioners are refused. `additional` mode therefore cannot bootstrap the first ADMIN.
- Running the command already requires shell and database access to the server, so these credentials are a second, independent requirement.

**Targets**
- The target must be a **new** account. A phone or email already in use is refused, including a customer's (email compared without regard to case).
- No account is ever converted to ADMIN, and there is no role-change path.
- The same name and password rules as the first ADMIN apply (12–128 characters).

**No public endpoint**
- Provisioning exists only in the command. The `provision-admin` profile starts no web server and no scheduled jobs. No REST endpoint for admin creation or role assignment is added.

**Concurrency and uniqueness**
- Both modes take the same transaction-scoped advisory lock, so runs are serialized.
- The unique phone and email indexes are the backstop: a race that gets past the check is refused as `ACCOUNT_EXISTS`.

**Audit**
- Every attempt writes to the existing append-only `audit_events` table. No migration is needed: the table already has `actor_type` SYSTEM/ADMIN, a nullable `actor_user_id`, `entity_id`, `reason` and JSON `details`.
- **Success:** action `admin.provisioned` with entity `USER` set to the new admin, written in the same transaction as the account.
  - Actor: `SYSTEM` for the first ADMIN; `ADMIN` = the provisioner for additional admins.
  - Details: `mode`, `outcome: SUCCEEDED`, `provisionedBy`, a masked target phone (last 3 digits) and whether an email was given.
  - The timestamp is `created_at`.
- **Refusal:** action `admin.provisioning_refused`, written in **its own transaction** so it survives the rollback.
  - `reason` is the code: `INVALID_NAME`, `WEAK_PASSWORD`, `ADMIN_ALREADY_EXISTS`, `ACCOUNT_EXISTS`, `PROVISIONER_NOT_FOUND`, `PROVISIONER_BAD_PASSWORD`, `PROVISIONER_NOT_ADMIN`, `PROVISIONER_DISABLED`.
  - Actor: the provisioner if it was authorized; otherwise `SYSTEM`, with the account the credentials named as `claimedProvisionerId`.
- Passwords, password hashes, and the full target phone and email are never recorded or logged.
- A refusal fails the command's startup, so the process exits non-zero.

**Separation of duties is unchanged.** `SECOND_APPROVER_REQUIRED` and the database's refusal of self-approval are untouched. A test proves that two provisioned admins complete request, approval and completion, and that the requester cannot approve their own refund.

## Alternatives considered
- **An admin-only REST endpoint to assign roles or create admins.** It adds an online attack surface for privilege escalation, a UI to design, and CSRF/session concerns. Rejected for the MVP; revisit with an audited admin screen if the store needs self-service.
- **Promoting an existing customer account.** Mixing customer history with admin rights invites escalation through a compromised customer account. Rejected; admins are created fresh.
- **Supported manual SQL.** No audit trail, no validation, easy to get wrong. Rejected.
- **A flag that lets `first` mode skip its "no ADMIN yet" check.** It would need no authorization from an existing admin. Rejected.

## Security implications and operational limits
- **Collusion or sock puppets.** One person with server access *and* an ADMIN password can create a second ADMIN they control, then approve their own refund requests through it. No technical control in this design prevents that; separation of duties ultimately rests on organisational control of server access and admin credentials.
  - The audit trail makes it visible: every admin's `admin.provisioned` record names who created it, and refunds record requester and approver.
  - Reviewing new `admin.provisioned` events is an operational duty.
- **Credentials in the environment.** Credentials are passed as environment variables, so operators should avoid shell history (for example an env file with restricted permissions, or `read -s`) and unset them afterwards.
- **No online brute-force path.** The command is not exposed over HTTP. Guessing a provisioner password needs server access, and every failed attempt is audited.
- **No way to remove or disable an admin yet.** Operationally that still needs SQL. It is out of scope here and a candidate for the same command.
- **Deployment blocker, outside this ADR.** The application context, and so this command, starts only when an `SmsSender` bean exists, and today only the dev/test fake provides one (ADR-0016). Until a production SMS provider is configured, the command cannot run on a production profile, and neither can the application.

## Verification (2026-11-19, branch `bugfix/qa-04`)
- **`AdminProvisioningIntegrationTests`: 12 tests.** The 2 existing tests are unchanged. The new ones cover:
  - audit of the first admin, and of a refused bootstrap;
  - an additional admin who can then sign in, with the provisioner identified by phone or by email;
  - refusal and audit of unknown, wrong-password, customer and disabled provisioners;
  - `additional` mode refusing to bootstrap;
  - an existing customer's phone or email never converted to ADMIN;
  - a weak password for the new admin;
  - 4 concurrent runs for one target, creating exactly one ADMIN;
  - no passwords or full contact details in the audit.
- **`AdminProvisioningCommandIntegrationTests`: 2 tests.** They run the real command in both modes and confirm that no web server starts, that a refusal fails the command, and that an unknown mode is rejected.
- **`ProvisionedAdminsRefundIntegrationTests`.** Two admins provisioned only through the service complete request, approval and completion. The requester's own approval gets `SECOND_APPROVER_REQUIRED`, and the audit shows a different requester and approver.
- **`RefundWorkflowIntegrationTests` (25 tests) unchanged and passing,** including the database's refusal of self-approval.
- **Mutation check.** With the role and enabled checks on the provisioner removed, 2 tests failed.
- **Full build:** `./mvnw -B clean verify` ran 519 tests, 0 failures. The spec check passes. No migration was added.
