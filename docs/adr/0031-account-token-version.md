# ADR-0031: Account token version, so re-enabling never revives a token

- Status: Accepted 2026-11-22 (with the acceptance notes below)
- Date: 2026-11-21
- Related: ADR-0025 (refresh-family lock), ADR-0028 (account state on every request), ADR-0029 (disable command). QA finding F-1 (`docs/qa/2026-11-21-admin-and-security-verification.md`, D-07).

## Context
- **Access tokens are stateless** RS256 JWTs that live up to 15 minutes. ADR-0028 refuses a disabled account's token on every request, but only while the account is disabled.
- **F-1 (verified):** re-enabling an account within an access token's lifetime made a token issued before the disable valid again (200, then 401, then 200). Nothing in the token tied it to the account's state.
- **Refresh tokens had a related gap on the database path.** The disable command (ADR-0029) revokes every session. A disable done by hand in the database revokes nothing until a refresh is presented *while* the account is disabled. Re-enabled by hand first, the old refresh cookie still worked, and a transparent refresh would have revived the session.

## Decision
- **An account token version.** The column `users.token_version` (migration V16) starts at 0.
  - A database trigger moves it on by one **whenever `enabled` changes**, whether through the operator command or a hand-written `UPDATE`.
  - It never changes otherwise, and an attempt to set it directly is ignored.
- **Access tokens carry it.** The issuer adds the claim `ver`.
  - Every request compares it with the account's current version, in the same primary-key lookup ADR-0028 already makes, so there's no extra query.
  - A mismatch gives the generic 401 `INVALID_TOKEN`.
- **Refresh families carry it.** `refresh_tokens.token_version` records the account's version at sign-in, and every successor copies it.
  - A refresh whose family version differs from the account's is refused (401), and the family is revoked (`ACCOUNT_DISABLED`).
  - So a transparent refresh can't revive a session from before a disable, even if nothing revoked it.
- **Fail closed.** A token without `ver`, or with anything but a whole number of 0 or more, is refused the same way.
- **Unchanged:** strict rotation and reuse detection (ADR-0023, ADR-0025), and the disable command's immediate revocation (ADR-0029).

## Security invariants
1. A token issued while the account had version *n* is accepted only while the account's version is *n*. Every disable or re-enable moves the version on, so nothing issued before the disable is accepted after it, ever again.
2. The version only moves forward. It can't be reset to an old value, which is what an old token would need.
3. A session (refresh family) keeps the version it signed in under. A refresh never issues tokens under a newer version than its family's.
4. **Every doubt gives 401:** a missing claim, a malformed claim, a mismatch, an unknown account, or a disabled account. The response doesn't say which.
5. Other accounts are unaffected: the version is per account.

## Concurrency
- **A refresh racing a disable.** The command takes each session lock before it updates the account (ADR-0025), so either:
  - the refresh commits first, and its successor is revoked by the command and carries the old version; or
  - the refresh waits, then finds its family revoked.
- **A sign-in racing a disable.** A sign-in doesn't take the session lock. If it reads the account (enabled, version *n*) before the disable commits, it saves a family and an access token with *n*, but the disable has moved the account to *n*+1. Both are refused from then on, including after a re-enable. Tested with an injected delay.
- **The account's state and its version are always read together**, from the same row in the same statement. So a token can never pair "enabled" with a version that is already out of date.

## Rollout
- **Deployment is a single API instance** (`docs/architecture.md`, "Single API instance in MVP"). The repository has no multi-instance or rolling-deployment configuration.
- **Plan: stop the old instance, then start the new one.**
  - V16 runs at start-up. Existing users and families get version 0, so existing refresh sessions keep working.
  - Access tokens issued before the release have no `ver`, so they're refused. The web app answers a 401 with one refresh (ADR-0023's interceptor), which issues a new token with `ver`. Users don't notice.
- **If the old and new instances ever overlap** (a blue/green switch), a refresh served by the old instance yields a token without `ver`, which the new instance refuses. The user is then sent to sign in once. This is never a security weakening.
- **Rollback to the previous release:**
  - The previous code ignores the new columns, so the schema stays compatible.
  - Sessions it creates get version 0 by default. If the new release returns, those families are refused for any account whose version is above 0, and those users sign in again. Again this is fail-closed.

## Remaining limitations
- **Logout does not end the access token.** After a logout, the access token (not the session) stays valid until it expires, at most 15 minutes. This predates F-1 and is out of scope here. The fix would be to bind access tokens to their session (the session-binding alternative below), or a deny-list.
- **A role change doesn't move the version.** It doesn't need to: ADR-0028 reads the role from the database on every request.
- **Re-enabling by hand** is covered by the trigger, but it is still unaudited. Use the operator command (ADR-0029).

## Alternatives considered
- **A per-account "valid from" timestamp compared with `iat`.** `iat` has one-second precision, and clocks between servers can differ: a skewed clock could let an old token pass (fail open). Rejected.
- **Binding access tokens to their session**, refused once the session is revoked. Stronger: it would also end tokens at logout. But it adds a query against the session table on every request, changes logout behaviour, and rewrites every test that builds tokens directly. Kept as the follow-up for the logout limitation.
- **Bumping the version only in the operator command.** It would miss a hand-written `UPDATE` during an incident. Rejected in favour of the trigger.

## Acceptance notes (2026-11-22)
The decision above is unchanged. Accepted with these three points stated explicitly:
- **Logout does not end access tokens.** After a logout, the access token stays valid for up to 15 minutes, as the Remaining limitations section says. The token version does not change this.
- **Direct SQL re-enable is not audited.** The trigger guarantees the version moves, but a hand-written `UPDATE` writes no `audit_events` row. The operator command (ADR-0029) is the audited path.
- **Rollout assumes a single application instance.** The fail-closed behaviour on overlap described under Rollout depends on a single running instance at a time. Multi-instance or blue/green deployment needs its own review.
