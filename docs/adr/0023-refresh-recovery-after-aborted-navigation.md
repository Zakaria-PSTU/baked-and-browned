# ADR-0023: Strict refresh rotation with browser-side recovery from aborted navigations

- Status: Accepted 2026-11-21 (revision 4, with the 2026-11-21 amendment below; supersedes revisions 1–3 on the unmerged `bugfix/qa-01` branch)
- Date: 2026-11-20
- Related: ADR-0025 (absolute session lifetime and revocation synchronization), ADR-0026 (key-bound refresh tokens, future)

## Context
QA-01 found:
1. The SPA keeps its access token in memory only, so every full page load calls `restore()`, which rotates the refresh token.
2. If a navigation, reload or tab close aborts that `POST /auth/refresh` after the server has rotated and committed, the browser drops the response and its `Set-Cookie`.
3. The next page presents the spent token. Reuse detection revokes the whole family and signs the user out.

Revisions 1–3 accepted the spent token again for a short window: IP + User-Agent bound, then up to 2 retries in 30 s. A security review rejected that: anyone holding a copy of the spent token, and the User-Agent, which travels in the same request, could obtain a live token within the window. Bounding the count and the time does not make a used-token replay safe.

## Decision
1. **The server keeps strict rotation.** Presenting a spent refresh token always revokes the family. There is no retry window and no migration for QA-01.
2. **The browser stops losing rotations to navigation.**
   - The refresh request is sent with `fetch(…, { keepalive: true })`, so the browser completes it, and applies its `Set-Cookie`, even after the page unloads.
   - Before sending, the SPA writes an in-flight marker to `localStorage`: `{ ts, xsrf }`, where `xsrf` is the readable `XSRF-TOKEN` value at that moment (not a secret).
3. **Recovery on the next page.**
   - Before any refresh, the SPA checks for a marker, inside the existing cross-tab Web Lock.
   - If the marker is younger than a wait budget (proposed 5 s), the SPA waits until `XSRF-TOKEN` changes, which shows the rotation completed and its cookies are stored, or until the budget runs out. Then it refreshes normally.
   - An older marker is discarded without waiting. The marker is always removed afterwards.
4. **Implementation note.** Angular 22's `HttpClient` honours `keepalive` only with the fetch backend (`withFetch()`), and the app uses the default XHR backend. The proposal is a native `fetch` for the single refresh call in `AuthService`. It already runs outside the auth interceptor, and this avoids switching every request to the fetch backend.

## Implementation (branch `bugfix/qa-01-refresh-recovery`, 2026-11-20)
- **`REFRESH_TRANSPORT`** (`frontend/src/app/core/auth/refresh-transport.ts`) sends the single refresh call as a native `fetch(…, { keepalive: true, credentials: 'same-origin' })` with `X-CSRF-Token`.
  - A non-2xx answer becomes an `HttpErrorResponse` with that status, and a network failure or an abort becomes status 0, so `AuthService` and the interceptor are unchanged.
  - Tests substitute an `HttpClient`-backed transport (`refresh-transport.testing.ts`).
- **`RefreshRecovery`** (`refresh-recovery.ts`) keeps the marker `bb.auth.refresh-in-flight.v1` = `{ ts, xsrf }` in `localStorage`. All storage access is guarded; a blocked storage only disables recovery.
- **`AuthService.refresh`, inside the `bb-auth-refresh` Web Lock:**
  1. Wait for an interrupted refresh: the budget is 5 s from the marker's `ts`, and the cookie is polled every 100 ms.
  2. Read the CSRF cookie.
  3. Write the marker.
  4. Send.
  5. Clear the marker on any HTTP response.
- **Two corrections found by the real-app probe** (`docs/qa/probes/qa-01-spa-recovery-probe.mjs`):
  1. An unload rejects the page's `fetch` promise while the keepalive request continues. The marker is therefore **kept on status 0**; clearing it there signed the user out after a navigation.
  2. The waiting page **keeps the marker until its wait ends**. Removing it first signed the user out after rapid reloads in Chrome and WebKit, when the waiting page was itself reloaded.
- **Real app, production build** (`ng build`), API with process-only overrides (`secure-cookies=false`, raised login, register and refresh limits). The proxy holds the refresh response 1.5 s after the API rotated, or drops it:

| Scenario | Chrome 145 | Firefox 137 | WebKit 18.4 (Windows) |
|---|---|---|---|
| Plain load | signed in | signed in | signed in |
| Navigation 150 ms into the refresh | recovered (1.8 s) | recovered (2.5 s) | recovered (2.0 s) |
| Two reloads 100 ms apart, then a load | recovered | recovered | recovered |
| Tab closed, new tab | recovered | recovered | recovered |
| Two tabs at once | both signed in | both signed in | both signed in |
| Response dropped (known limit) | signed out | signed out | signed out |

- **Still required before production:**
  - Safari on iOS and macOS, and Android Chrome. Not available on the Windows test machine; tracked as a launch item.
  - Same-origin production hosting. The app calls `/api/v1` relative; a cross-origin API would need a CORS preflight for the keepalive request, which is untested.

## Evidence (2026-11-20, local API with production auth code; proxy holding or dropping the refresh response after the server rotated)

| Scenario | Chrome 147 | Firefox 137 | WebKit 18.4 (Windows) |
|---|---|---|---|
| Plain fetch, navigation 150 ms later (QA-01 today) | signed out (401) | signed out | signed out |
| keepalive fetch, navigation | recovered: XSRF changed after ~1.4 s, then 200 | recovered (~1.4 s) | recovered (~1.4 s) |
| keepalive, 3 navigations 100 ms apart | recovered | recovered | recovered |
| keepalive, tab closed, new tab opened | recovered | recovered | recovered |
| Two tabs at once, no lock (control) | one 401, session ended | same | same |
| Two tabs with the Web Lock | both 200 | both 200 | both 200 |
| Response lost in the network (connection dropped) | signed out | signed out | signed out |
| Slow response (3.5 s) within the 5 s budget | recovered | recovered | recovered |
| Slow response (7 s) beyond the budget | signed out | signed out | signed out |
| Stale marker (60 s old) | ignored, no wait, 200 | same | same |
| Orphan marker, nothing in flight | waited the full budget once, then 200 | same | same |
| XSRF cookie changes on every refresh | yes | yes | yes |

- The tests ran on `http://localhost` with `secure-cookies=false` for this local process only. WebKit does not expose `Secure` cookies to `http://localhost` pages; production is HTTPS.
- WebKit is Playwright's Windows build, not Safari on macOS or iOS. Before production, recovery must be re-run on Safari on iOS and macOS, and on Android Chrome.

## Consequences
- The navigation, reload and tab-close cases of QA-01 are fixed with no server-side replay exposure.
- **Not fixed:** a response lost in the network (mobile drop after the server committed), and a response slower than the wait budget. Both still sign the user out. Carts are kept, and the user signs in again. ADR-0026 is the proper fix for these.
- A page load that finds an in-flight marker can wait up to the budget, once. An orphan marker, for example after a crash, costs one such wait.
- The marker holds no secret, and XSS exposure is unchanged: script on the page could already call refresh.

## Rejected alternatives (replay analysis)
- **A: accept the spent token, 2 retries within 30 s** (revisions 2–3). Anyone with a copy of the spent token, plus the User-Agent sent in the same request, obtains a live token within the window. The victim's next refresh revokes the family. But if the victim stays away, the attacker keeps the session.
- **B: the same within 15 s.** Same risk with a shorter window. A bounded replay is still a replay.
- **D: client-generated request id required on retry.** The id travels in the same request as the token, so an interceptor has it too. It helps only against theft of the cookie store alone. Not a proof of client identity, and it needs an API change.
- **IP binding** (revision 1): signs out mobile users and is weakened by carrier-grade NAT. Little protection against device malware.
- **E: key-bound refresh (proof of possession).** Not rejected; deferred to ADR-0026.

## Amendment (2026-11-21, on acceptance)
- Merged in PR #23 (`6cef240`). The "Implementation" section above describes that work. The branch it names is merged.
- Testing on real Safari (iOS, macOS) and Android Chrome, and the same-origin hosting check, remain launch conditions in `docs/launch-readiness.md`.
