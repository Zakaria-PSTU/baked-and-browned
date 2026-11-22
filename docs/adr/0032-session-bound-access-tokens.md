# ADR-0032: Bind access tokens to their session, so logout ends them

- Status: Proposed
- Date: 2026-11-22
- Related: ADR-0025 (refresh-family lock, revocation), ADR-0028 (account state on every request), ADR-0029 (disable command), ADR-0031 (account token version, "Remaining limitations")

## Context
- **Access tokens are stateless** RS256 JWTs that live up to 15 minutes. Until now they named the account (`sub`), its role and its token version (`ver`), but not the session they came from.
- **Logout revoked the refresh family and nothing else.** The per-request check (ADR-0028, ADR-0031) asked about the account only, so after a logout, a reuse detection or a session's end, the access token kept working until it expired. ADR-0031 recorded this as a remaining limitation and named session binding as the follow-up.
- A session is a refresh family (`refresh_tokens.family_id`). The code already keeps its state in one place:
  - A refresh marks the spent token as used (`used_at`) and leaves `revoked_at` empty.
  - Logout, reuse detection, the disable and enable command and a version mismatch at refresh all call `revokeFamily` or `revokeAllOfUser`, which set `revoked_at` on every row of the family that does not have it.
  - So a live family always has at least one refresh token with `revoked_at IS NULL`, and a revoked family has none.

## Decision
- **A new claim, `sid`,** holds the id of the session (the refresh family) the token was issued for. `AccessTokenIssuer.issue(user, tokenVersion, familyId)` adds it. A refresh keeps the family, so `sid` stays the same across rotations.
- **Each request that presents a token also checks that its session is live,** in the same single statement as the account lookup (no extra round trip):
  ```sql
  SELECT role::text, enabled, token_version,
         EXISTS (SELECT 1 FROM refresh_tokens r
                 WHERE r.family_id = ? AND r.user_id = users.id AND r.revoked_at IS NULL) AS session_live
  FROM users WHERE id = ?
  ```
  The match on `user_id` means a token can never be honoured through another account's session.
- **Fail closed.** A token without `sid`, with a `sid` that is not a canonical UUID string, naming a session that does not exist, belongs to another account or has no un-revoked token, gets the same generic 401 `INVALID_TOKEN` as every other doubt. The response does not say which.
- **Migration V17 adds a partial index** `idx_refresh_family_active ON refresh_tokens (family_id) WHERE revoked_at IS NULL`. It makes the probe one index lookup for a live family, and keeps it cheap for a revoked one, whose rows can number in the thousands. No data changes.
- **Unchanged:** the logout endpoint, refresh rotation and reuse detection, the family lock, and `ver`. `ver` stays because it covers a hand-written `UPDATE` of `enabled`, which revokes no session; `sid` covers the end of a session.
- **Not in the web app or the API contract:** the token is opaque to the web app, which already answers a 401 with one refresh and then a sign-out.

## Security invariants
1. A token is honoured only while the session it names is live. Logout, a reuse detection, the disable and enable command and a refresh refused for a version mismatch each leave the family without an un-revoked token, so each stops its access tokens on the next request.
2. Sessions are independent. Ending one family does not affect the same account's other sessions.
3. Every doubt gives 401: a missing or malformed `sid`, an unknown session, another account's session, a revoked session.
4. The token carries a random session id and no personal data.

## Consequences
- **Logout ends the session's access tokens at once,** in every tab and for every token the session had issued, including the one issued before a refresh. A request already past the check finishes (as in ADR-0028).
- **Reuse detection ends the compromised session's access tokens at once,** the victim's included. The victim's web app refreshes, is refused, and signs the user in again.
- **One more index probe per authenticated request,** inside the statement ADR-0028 already makes. Its cost has not been measured; check the plan against realistic data before relying on it.
- **Rollout assumes a single API instance** (`docs/architecture.md`), as in ADR-0031.
  - Stop the old instance, then start the new one. V17 runs at start-up.
  - Access tokens issued before the release have no `sid` and are refused once. The web app answers with one refresh, which issues a token with `sid`. Refresh sessions are unaffected.
  - If the old and new instances ever overlap, a token from the old one is refused by the new one and the user signs in once. This never weakens security.
- **Rollback is compatible:** the previous release ignores `sid` and the index.
- **The purge of long-expired sessions** (`RefreshTokenCleanup`) deletes their rows, which also ends their access tokens. That is harmless; the session is long over.

## Limits
- **A session's expiry does not stop its access tokens by itself.** Only revocation, or the purge deleting the rows, does. After an idle or absolute expiry an access token can live for the rest of its 15 minutes. Rejecting tokens past the family's absolute lifetime was considered and not done (below).
- **A hand-written `UPDATE` that disables an account** is stopped by `ver`, not by `sid`, because it revokes no session. Use the operator command (ADR-0029), which does both.
- **Logging out one device does not log out another.** "Log out everywhere" is not part of this decision.

## Alternatives considered
- **A deny-list of `jti` values.** It needs a table, expiry cleanup and a write on every path that ends a session (logout, reuse, disable). The family's own state already says whether it is live.
- **A dedicated `sessions` table.** Cleaner, but it needs a backfill and changes to the rotation and lock code. Worth revisiting if an "active devices" feature is wanted.
- **Also refusing a token once its family's absolute lifetime has ended.** It would trim the 15-minute tail at the end of a session. It is another behaviour change, left out for now.
- **Shorter access tokens.** They narrow the window without closing it and multiply refreshes.
- **A short cache of the lookup.** It brings the delay back (see ADR-0028).
