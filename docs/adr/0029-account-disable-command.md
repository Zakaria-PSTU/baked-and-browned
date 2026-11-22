# ADR-0029: Disable and re-enable accounts through the operator command

- Status: Accepted 2026-11-21
- Date: 2026-11-20
- Builds on: ADR-0024 (operator-run admin provisioning), ADR-0025 (refresh-family lock), ADR-0028 (account state checked on every request)

## Context
- An account could only be disabled by editing `users.enabled` in the database. Since ADR-0028 its access tokens are refused from its next request, but two gaps remained:
  1. **Nothing recorded who disabled it, or why.** A database edit is not in `audit_events`.
  2. **Re-enabling revived stolen sessions.** Disabling revoked nothing: a refresh family was revoked only when one of its tokens was next presented. A stolen refresh token that was not used while the account was disabled became valid again once the account was re-enabled. After an incident, that is the dangerous case.
- ADR-0024 keeps admin and role management off the web: no REST endpoint creates admins or changes roles. A web "disable" button would also let one compromised ADMIN lock every other ADMIN out, unless more rules were added.

## Decision
- **Two more modes of the `provision-admin` command:** `ADMIN_PROVISION_MODE=disable` and `enable`.
  - **Target:** `ACCOUNT_IDENTIFIER`, a phone in +880 form or an email (case-insensitive).
  - **Reason:** `ACCOUNT_CHANGE_REASON`, 3–500 characters, required and recorded.
  - **Authorization:** `PROVISIONER_IDENTIFIER` and `PROVISIONER_PASSWORD` of an enabled ADMIN, checked exactly as in ADR-0024. The same password sources apply (secret file or process environment only), and the same rule that no web server may run.
- **Disable:**
  - sets `users.enabled = false`;
  - revokes every active refresh token of the account (`revoke_reason = ACCOUNT_DISABLED`);
  - writes `account.disabled` with the ADMIN as actor, the reason, the target's id and role, and the number of sessions revoked.
- **Enable:**
  - sets `enabled = true`;
  - revokes any refresh token still active, which can only come from a sign-in that raced the disable;
  - writes `account.enabled`.
  - No earlier session comes back: the user signs in again.
- **Refused, and recorded once as `account.status_change_refused` with a code:**
  - an unknown account (`ACCOUNT_NOT_FOUND`);
  - an account already in the requested state (`ALREADY_DISABLED` / `ALREADY_ENABLED`);
  - a reason that is too short or too long (`INVALID_REASON`);
  - the provisioner refusals of ADR-0024;
  - disabling **the last enabled ADMIN** (`LAST_ENABLED_ADMIN`), so the shop is never locked out.
  - An ADMIN may disable themselves while another enabled ADMIN remains.
  - A disabled ADMIN can authorize nothing, not even its own re-enabling.
- **Failures** roll back and are recorded as `account.status_change_failed`.
- **Locks, in this order:**
  1. the provisioning advisory lock, so two runs cannot together disable the last two ADMINs;
  2. each of the account's refresh-family locks (ADR-0025), in a fixed order;
  3. the account row.
  - A refresh takes its family lock before its token and account rows, so the two never wait on each other in opposite orders.
  - A rotation in progress commits first, and its successor is then revoked with the rest.
- **Not included:** demoting an ADMIN to CUSTOMER, and any web screen. Disabling covers the incident case. A demotion mode or a screen would need their own decision.

## Consequences
- Disabling an account takes effect on its next request (ADR-0028), ends all its sessions at once, is attributable and auditable, and cannot be undone by accident: re-enabling does not restore sessions.
- Operators need the same server access as for provisioning. An ADMIN cannot disable an account from the web app.
- A sign-in that races a disable can create a session after the revocation. It is useless while the account is disabled (refused by ADR-0028 and at refresh), and the enable revokes it.
- Audit details never contain a password or the target's full phone or email (masked phone, an "email given" flag, the account id).

## Alternatives considered
- **A web screen for ADMINs.** It is faster to reach during an incident, but it is the first role or state endpoint (against ADR-0024), and a compromised ADMIN could disable the others. That would need a second approval or other safeguards. Rejected for now.
- **Leave revocation to the next refresh (lazy).** Simpler, but it is exactly the "re-enabling revives a stolen token" gap.
- **A demotion mode.** Rarely needed, and disabling plus provisioning a new account covers it.

## Amendment (2026-11-22, QA finding F-4)
- **Tokens issued before a disable stay invalid after a re-enable.** Gap 2 and the Enable bullet above were about refresh tokens. The access tokens (up to 15 minutes) were a separate gap, found as QA finding F-1: a token issued before the disable became valid again after the re-enable.
- It is closed by the account token version (migration V16, merged in PR #36; the design is ADR-0031, which is still Proposed). A database trigger moves `users.token_version` on by one every time `enabled` changes, whether through this command or a hand-written `UPDATE`. Access tokens and refresh families carry the version they were issued under and are refused when it differs from the account's.
- **So, with V16 in place:** after a disable followed by a re-enable, neither an access token nor a refresh token issued before the disable is accepted, and the user signs in again. Access tokens issued before V16 was deployed carry no version and are refused (the web app replaces them with one refresh).
- **Limits.** This covers the disable and enable path only. A logout still leaves its access token valid until it expires, at most 15 minutes (see ADR-0031, "Remaining limitations").
