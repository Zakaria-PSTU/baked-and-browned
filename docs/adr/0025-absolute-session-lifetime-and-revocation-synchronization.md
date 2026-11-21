# ADR-0025: Absolute session lifetime and revocation synchronization

- Status: Accepted 2026-11-21 (with the 2026-11-21 amendment below)
- Date: 2026-11-20
- Related: ADR-0023 (strict rotation), the refresh-token rotation in architecture.md

## Context
1. **No absolute lifetime.** Every rotation issues a token with `expires_at = now + 14 d`. A family therefore never ends while it keeps refreshing, so a stolen refresh token can keep a session alive indefinitely while the victim is away. Admins, who can approve and complete refunds, get the same 14 days as customers.
2. **Revocation races** (reproduced deterministically, 3 runs out of 3, `RevocationRaceIntegrationTests`, first on `investigate/qa-01-revocation-race`):
   - A **logout** racing a refresh left the new successor live, and it could still be refreshed.
   - **Reuse detection** racing a refresh left the new successor live and refreshable. So whoever refreshes at the moment of detection keeps the session.
   - Two concurrent reuse detections **deadlocked** (PostgreSQL 40P01). One caller got a 500 instead of a 401.
   - An account disable racing a refresh is caught at the next refresh. Disabling is lazy; there is no disable API.

   **Cause:**
   - `revokeFamily` is one `UPDATE … WHERE family_id = ? AND revoked_at IS NULL`. Under READ COMMITTED it waits for the refresh's row lock, then re-checks only the rows it already found. The successor the refresh inserted is not in its snapshot, so it survives.
   - Each request locks its own presented token first, then the `UPDATE` needs the others', so two revocations can lock in opposite orders.

## Decision
### Revocation synchronization (smallest safe fix)
- **One lock per family, taken first.** Every operation that rotates or revokes a family takes `pg_advisory_xact_lock(<refresh-family class id>, <hash of family_id>)` before any row lock: refresh, logout, reuse revocation, disable-on-refresh, and any future admin "sign out everywhere".
  - The two-argument form keeps these keys apart from the one-argument provisioning lock.
  - A hash collision only serializes two unrelated families, which is harmless.
- **Order inside refresh:**
  1. Look up the presented token by hash, without locking, to learn its family.
  2. Take the family lock.
  3. Re-read the token `FOR UPDATE` and proceed as today.

  Logout looks up the family and takes the same lock before `revokeFamily`.
- **Effect.**
  - A revocation either runs after the competing refresh committed, so its `UPDATE` snapshot includes the new successor, or before it, so the refresh then finds the token revoked.
  - All family operations lock in the same order, so they can no longer deadlock.
  - The cost is one advisory-lock call per refresh or logout, held for the length of a short transaction.
- **No migration** for this part.
- **Implementation (branch `bugfix/new-1-revocation-race`, 2026-11-20).** `AuthService.lockFamily` runs `SELECT pg_advisory_xact_lock(?, ?)` with class id `0x5246` ("RF") and `UUID.hashCode()` of the family id. It uses `JdbcTemplate` in the JPA transaction, like the provisioning lock.
  - `RefreshTokenRepository.findFamilyIdByTokenHash` reads only the family id. No entity is loaded before the lock, so the `FOR UPDATE` re-read cannot return a stale managed instance.
  - Refresh (including the reuse and disable-on-refresh revocations) and logout take the lock. There is no other caller of `revokeFamily` today.
  - The three previously failing `RevocationRaceIntegrationTests` pass: logout and reuse detection leave every family token revoked, and concurrent reuse detections return `[401, 401]`.
  - The absolute lifetime below is **not** part of this change.

### Absolute session lifetime
- **New column.** Each family gets an absolute end, `family_expires_at`, stored on each of its tokens. It is set at login to `login time + absolute(role)` and copied unchanged to every successor.
- **Token expiry:** `expires_at = min(now + idle(role), family_expires_at)`.
- **Proposed values:**
  - CUSTOMER: idle 14 d, absolute 30 d.
  - ADMIN: idle 8 h, absolute 12 h.

  These are configurable per role and validated at startup (idle ≤ absolute).
- **Cookie lifetime.** The refresh cookie's `Max-Age` follows the new token's `expires_at` instead of a fixed 14 days.
- **At the absolute end** the refresh is refused with the normal 401. The user signs in again, which starts a new family.
- **Migration (new; V13, the number is free now that QA-01's V13 is not merged):** add `family_expires_at TIMESTAMPTZ` and backfill existing rows.
  - The backfill sets it to the earliest `created_at` in the family plus the role's absolute lifetime. Each token's `expires_at` is then lowered to `family_expires_at` where it was later. (Revision of 2026-11-20: the first draft capped the end at the current `expires_at`, which would have broken the CHECK and kept old admin sessions alive.)
  - The backfill hard-codes the default absolute lifetimes (30 d, 12 h). Later configuration changes apply to new sessions only.
  - The column then becomes `NOT NULL`, with `CHECK (expires_at <= family_expires_at)`.
  - Existing admin sessions older than 12 h end at their next refresh. That is intended.

- **Implementation (branch `bugfix/absolute-session-lifetime`, 2026-11-20).**
  - Configuration `app.security.sessions.{customer,admin}.{idle,absolute}` replaces `app.security.refresh-token-ttl`. `SecurityProperties.SessionLifetime` rejects missing, zero or negative values and idle longer than absolute, so startup fails.
  - Login sets `family_expires_at = now + absolute(role)`. Every rotation copies it and issues `expires_at = min(now + idle(role), family_expires_at)`, using the user's **current** role for the idle part.
  - Past the end, the token is expired, so refresh returns the normal 401 and clears the cookies.
  - `AuthSession` carries the token's remaining lifetime, which `AuthCookies` uses as `Max-Age` for both the refresh and `XSRF-TOKEN` cookies.
  - Migration V13 as above, with `ck_refresh_within_family`.

## Consequences
- Logout, reuse detection and disable leave no usable token once they have committed. Concurrent revocations return 401, not 500.
- Stolen tokens can no longer prolong a session indefinitely: at most 30 d for customers and 12 h for admins.
- Admins sign in at least every 12 h, and after 8 h idle.
- The existing idle (rolling) expiry is kept, but never extends past the absolute end.

## Tests
- Done (family lock): the 3 previously failing revocation-race tests are green: logout, reuse and concurrent reuse.
- Done (absolute lifetime):
  - `RevocationRaceIntegrationTests`: logout and reuse detection started together with a refresh, over 20 rounds.
  - `SessionLifetimeIntegrationTests` (controllable clock):
    - continuous refreshing stops at `family_expires_at`, and `expires_at` never exceeds it;
    - customer 14 d idle and 30 d absolute; admin 8 h idle and 12 h absolute;
    - the cookie `Max-Age` matches;
    - the current role sets the idle part;
    - expiring one family leaves the user's other families unaffected.
  - `AbsoluteSessionLifetimeMigrationTests`: the V13 backfill on existing customer and admin families, including an admin family older than 12 h.
  - `SessionLifetimeTest`: configuration validation and startup failure.
  - `IdentityAndAuditSchemaTests`: the `NOT NULL` and `CHECK` constraints.
- Still to do: an admin-disable path, if one is added, revoking under the family lock.

## Amendment (2026-11-21, on acceptance)
- An admin disable path now exists (ADR-0029, PR #32). It takes each of the account's family locks, then revokes all of the account's tokens.
  - That supersedes "Disabling is lazy; there is no disable API" in the Context, and "There is no other caller of `revokeFamily` today".
  - It completes the "Still to do" item under Tests.
- `RevocationRaceIntegrationTests` covers a disable racing a refresh, and that test fails without the locks.
- Both parts of this ADR are merged. The branches named in the implementation notes are merged.
