# ADR-0028: Check the account's state on every authenticated request

- Status: Accepted 2026-11-21 (with the 2026-11-21 correction below)
- Date: 2026-11-20
- Related: ADR-0025 (session lifetime and revocation), ADR-0017 (refund approval), architecture.md (Identity and security)

## Context
- Access tokens are stateless RS256 JWTs that live up to 15 minutes. They carry the user's id (`sub`) and role (`roles`).
- Until now the server trusted the token alone: signature, issuer, audience, expiry and the `roles` claim.
- Disabling an account stops it at the next refresh: `AuthService.refresh` refuses it and revokes the family with `ACCOUNT_DISABLED`. Its current access token kept working everywhere else until it expired.
- Only `/auth/me` and the refund steps (`RefundWorkflow.requireActiveAdmin`) looked the account up again.
- So a disabled or demoted ADMIN kept every other admin power for up to 15 minutes. That includes verifying manual payments, recording cash collection, cancelling orders (which opens refunds), resolving reconciliation cases and editing the catalogue.
- There is no disable or demote API yet. It is done in the database, for example after an incident, which is exactly when the delay matters.

## Decision
- **Each request that presents a valid access token re-reads the account:** `SELECT role, enabled FROM users WHERE id = sub` (one primary-key lookup), done in the JWT authentication converter (`AccountStateJwtConverter`) after the token's signature and claims have been verified.
- **Missing or disabled account → 401 `INVALID_TOKEN`.** The detail is the same generic text as any bad token, so the response does not reveal whether the account exists or is disabled. The web app treats a 401 by refreshing; the refresh is refused for a disabled account, so the user is signed out.
- **The role comes from `users.role`, not from the token.**
  - A demoted ADMIN acts as a CUSTOMER from their next request and gets 403 on `/admin/**`.
  - A promoted user gets the new role without signing in again.
  - The `roles` claim stays in the token, is still required by the decoder, and is used by the web app for display. It is no longer used for authorization.
- **No cache.** A cache would add a dependency and bring back a delay. The lookup is by primary key on a small table, so its cost is negligible at this shop's traffic.
- **Requests without a token are not looked up.** Public endpoints (catalogue, slots, zones, gateway webhooks, health) answer anonymous requests as before.
  - A request to a public endpoint that does present a token is authenticated like any other, as it already was for expired tokens. So a disabled account's token gets 401 there too.
- **Service checks stay as a second layer.** `RefundWorkflow.requireActiveAdmin` still re-checks, inside the transaction that moves money.

## Consequences
- Disabling or demoting takes effect on the account's next request instead of up to 15 minutes later. Revocation of the refresh family still happens at the next refresh, as before (ADR-0025).
- One extra query per authenticated request. If traffic ever makes this matter, a short cache can be added deliberately, with its delay documented.
- A token whose `sub` is not a UUID, or names no user, is refused even if correctly signed. Only the server can sign tokens, so this changes nothing for real clients.
- A request already past the check when the account is disabled finishes. Operations that move money re-check inside their transaction, which closes that window for refunds.

## Alternatives considered
- **Shorter access tokens (for example 2 minutes).** This narrows the window without closing it, and multiplies refreshes.
- **A token deny-list.** It needs a store and its own expiry handling, so it is more moving parts than one lookup.
- **Checking in each service, like `RefundWorkflow`.** That is easy to forget on a new endpoint. A check at the door covers every endpoint by default.
- **A short cache (for example 30 s).** It adds a dependency and a delay before the change takes effect. It was rejected for now.

## Correction (2026-11-21, on acceptance)
- **The web app takes the role from the sign-in and refresh response, not from the token** (`auth-session.ts`). That corrects "used by the web app for display" above.
  - After a demotion, the admin menus stay visible until the session's next refresh, at most 15 minutes.
  - The API refuses admin actions at once.
- Disabling is now done with the operator command (ADR-0029). That supersedes "There is no disable or demote API yet" in the Context. Demotion still has no supported path.

## Amendment (2026-11-22, QA finding F-4)
- **When the admin menus disappear.** The Correction above says they stay visible until the session's next refresh. Since F-2 (PR #37) that is no longer the whole story, and the text is replaced by this:
  - The web app still takes the role from the sign-in and refresh response, so a demoted admin's page keeps showing the admin menus until something tells it otherwise.
  - When an admin API request (`/api/v1/admin/**`) is refused with 403 while the tab believes the user is an ADMIN, the app calls `GET /auth/me`. If the account's current role is not ADMIN, the tab updates its role: the admin navigation is removed, the admin route guard closes admin pages, and the shell shows "Admin access removed" over a read-only page (`admin-access.ts`, `admin-access.interceptor.ts`).
  - A failed `/auth/me` check concludes nothing and changes nothing.
  - **This is per tab.** Each tab learns of the demotion on its own first refused admin request.
  - **A tab that makes no admin request keeps showing the menus** until its next refresh or reload (at most 15 minutes for an open page). Removing the menus before the first admin request was considered and not done.
- **What did not change:** the API refuses admin actions at once, from the account's next request. The menus are display only and give no access.
- **Disabled accounts.** The 401 path is unchanged: the refresh is refused and the user is signed out.
