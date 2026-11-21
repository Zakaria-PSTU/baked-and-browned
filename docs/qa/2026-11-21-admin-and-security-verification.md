# Admin and security verification (step 12)

- **Date:** 2026-11-21
- **Commit tested:** `8e31532` (`main`; branch `qa/admin-security-verification` adds only this report)
- **Method:** the real application in a browser (installed Chrome, driven by `playwright-core` 1.49.1 from outside the repository), plus direct API calls and read-only database queries for the evidence.
- **Data:** synthetic only.
- **Providers:** fake SMS and fake payment services. No real provider was contacted.

## 1. Executive summary

Four areas were verified in the running application: the refund screen, disabled accounts, demotion, and the production readiness check.

- **Results:** 21 scenarios. **20 PASS, 1 FAIL, 0 BLOCKED.**
- **Findings:** 4, all **Low**. **No Critical, High or Medium findings.**
- **Server-side boundaries held** in every test where the screen could have been bypassed:
  - self-approval of a refund;
  - customers on refund endpoints;
  - a second payout;
  - a demoted admin's token;
  - a disabled account's token.
- **Duplicate submissions:** double clicks produced exactly one decision, one request and one payout record.
- **The one failure (D-07, finding F-1):** an access token issued before an account was disabled works again if the account is re-enabled within that token's 15-minute life. Refresh tokens stay revoked, as designed. This contradicts one sentence in ADR-0029.
- **The other three findings** are a misleading error message after a demotion, startup order under `prod` (migrations run before the readiness refusal), and an ADR wording point.

None of the findings blocks a release. All four are proposed below as separate follow-up tasks; **nothing was fixed in this step.**

## 2. Environment and commit tested

| Item      | Value                                                                                                                                    |
| --------- | ---------------------------------------------------------------------------------------------------------------------------------------- |
| Commit    | `8e31532` on `main`, unchanged; the QA branch adds only this report                                                                      |
| Host      | Windows 11, Docker Desktop 29.5.3, JDK 25, Node 24, installed Chrome                                                                     |
| API       | `./mvnw spring-boot:test-run` with `--spring.profiles.active=dev`: a throwaway PostgreSQL 17.12 (Testcontainers) and a throwaway JWT key |
| Web       | `npm start` (Angular dev server, `/api` proxied to :8080)                                                                                |
| Seed      | `.claude/skills/run-app/seed.sql`: one product, the DHANMONDI zone and one slot two days ahead                                           |
| Admins    | Two synthetic admins ("Alpha", "Bravo"), created with the supported `provision-admin` command (first and additional modes)               |
| Customers | Three synthetic customers registered through the web app                                                                                 |
| Jar       | `backend-0.0.1-SNAPSHOT.jar` built from the same commit, for the operator command and the `prod` startup check                           |

Everything was removed at the end: Testcontainers deleted the database container when the API stopped. Screenshots were kept outside the repository and are not committed.

**Two environment events, both recorded rather than worked around silently:**

- **Login rate limit:** the limit (10 per 5 minutes per address) refused one of the QA script's sign-ins (HTTP 429) after the earlier scenarios. That's the control working. The run waited out the window and continued; no control was bypassed.
- **A 12-minute freeze:** at 17:57 Dhaka time the API logged `Thread starvation or clock leap detected (housekeeper delta=11m55s)`. One request in D-04's first attempt failed during it. Afterwards the API and database answered normally (3 of 3 requests in 0.02–0.03 s). The system log showed no sleep or resume event in the last hour, so the cause is unknown (assumption: host load). D-04 was rerun afterwards.

## 3. Scope and exclusions

**In scope** (the four areas agreed in the step 12 plan):

1. **Refund screen (N-05):**
   - a customer-cancellation refund through approval and payout;
   - a partial admin request from the order page, where the requester can't approve and withdrawing shows "Withdrawn";
   - the dashboard tile;
   - the page at phone width.
2. **Disabled account (ADR-0028, ADR-0029):**
   - the disable command signs out a signed-in customer;
   - sign-in is refused while disabled;
   - re-enabling restores sign-in;
   - the last enabled admin can't be disabled.
3. **Demotion (ADR-0028 correction):** the API refuses a demoted admin at once; what the browser shows meanwhile.
4. **Production readiness check (ADR-0030):** the built jar under `prod` with development defaults.

**Excluded, as agreed:**

- the full checkout, payment and reconciliation QA pass;
- real SMS and payment providers;
- real Safari and Android devices;
- production hosting, HTTPS and cookies. Locally everything is `localhost` over plain HTTP, so cookie `Secure` behaviour wasn't exercised.

**Two deliberate deviations, required by the scenarios themselves:**

- **Demotion was done in the throwaway database (SQL).** There is no supported demotion path (ADR-0028). The role was restored afterwards.
- **Area 4 used `.invalid` provider addresses.** The `prod` run had to configure an SMS sender and an enabled SSLCommerz gateway. Their addresses used the reserved `.invalid` domain, which never resolves, with dummy credentials. Neither adapter makes a call at startup.

## 4. Test scenario matrix

| ID   | Area      | Scenario                                                                                                 | Result                |
| ---- | --------- | -------------------------------------------------------------------------------------------------------- | --------------------- |
| R-01 | Refunds   | A customer-cancelled paid order appears under "To approve"                                               | PASS                  |
| R-02 | Refunds   | The dashboard Refunds tile counts open refunds and links to the page                                     | PASS (rerun, see 5.1) |
| R-03 | Refunds   | Approve needs a note and says no money moves; a double click sends one decision                          | PASS                  |
| R-04 | Refunds   | Payout needs a reference and says it sends no money; history shows Sent; one payout record               | PASS                  |
| R-05 | Refunds   | The API refuses a second payout and refuses customers                                                    | PASS                  |
| R-06 | Refunds   | Partial request from the order page: over-limit validation, confirmation, double click gives one request | PASS                  |
| R-07 | Refunds   | The requester sees Approve disabled; the API refuses the self-approval                                   | PASS                  |
| R-08 | Refunds   | Withdrawing an own request shows "Withdrawn"                                                             | PASS                  |
| R-09 | Refunds   | A second admin approves another admin's request in the UI                                                | PASS                  |
| R-10 | Refunds   | The refunds page at 390 px has no sideways scrolling                                                     | PASS                  |
| D-01 | Disable   | A signed-in customer is signed out on their next click after a disable                                   | PASS (rerun, see 5.2) |
| D-02 | Disable   | The old token gets 401; all sessions are revoked; the change is audited                                  | PASS                  |
| D-03 | Disable   | Sign-in to the disabled account is refused with the usual message                                        | PASS                  |
| D-04 | Disable   | Re-enabling allows sign-in, and no session from before the disable comes back                            | PASS (rerun, see 5.2) |
| D-05 | Disable   | The last enabled admin can't be disabled; the refusal is audited                                         | PASS                  |
| D-06 | Disable   | A wrong provisioner password is refused generically and changes nothing                                  | PASS                  |
| D-07 | Disable   | An access token issued before a disable stays refused after a quick re-enable                            | **FAIL (F-1, Low)**   |
| M-01 | Demotion  | The API refuses the demoted admin's existing token on admin endpoints                                    | PASS                  |
| M-02 | Demotion  | What the open admin pages show before a refresh                                                          | PASS (observed; F-2)  |
| M-03 | Demotion  | After one reload, the admin menus are gone                                                               | PASS                  |
| P-01 | Readiness | `prod` with development defaults refuses to start and names every setting, never a value                 | PASS (F-3 observed)   |

## 5. Detailed expected versus actual results

Evidence types: **UI** is a browser observation (a screenshot kept locally); **API** is an HTTP status and error code; **DB** is a read-only query on the throwaway database; **LOG** is the API or command output.

### 5.1 Refund screen

**R-01: a refund from a customer cancellation**

- **Preconditions:** customer One pays an online order on the fake gateway (order Confirmed, paid online).
- **Steps:** the customer cancels it. Admin Alpha opens `/admin/refunds`.
- **Expected:** one card under "To approve", requested by "the customer, by cancelling", for the full amount, linking to the admin order page.
- **Actual:** as expected. The requester line reads "the customer, by cancelling" with the Dhaka time; the amount is ৳1,310.00; the link is `/admin/orders/<id>`. (UI)

**R-02: the dashboard tile**

- **Expected:** a Refunds tile whose value equals the API's `refundsToProcess`, linking to `/admin/refunds`.
- **First attempt:** recorded FAIL, but **the cause was the test script.** Its selector read only the tile's "Open" link text. The screenshot taken at that moment shows "Refunds 1 · To approve or pay out", highlighted, while the API reported 1.
- **Rerun, with a selector for the whole tile:** value 1 = API `refundsToProcess` 1 = open refunds listed 1. "Open" navigates to `/admin/refunds`. (UI, API)

**R-03: approving**

- **Steps:** Alpha clicks Approve with no note; adds a note; clicks Approve; double-clicks "Yes, approve".
- **Expected:**
  - a note is required;
  - the confirmation says no money moves;
  - exactly one decision is recorded.
- **Actual:**
  - "Write a note for the decision (3 to 500 characters).";
  - the confirmation contains "No money moves yet";
  - the banner reads "The refund for order … is approved. Send the money, then record its reference.";
  - status APPROVED.
  - The audit at the end of the run shows exactly one `refund.approved` record for this refund (2 in total across R-03 and R-09). (UI, API, DB)

**R-04: recording the payout**

- **Steps:** "Record refund sent" with no reference; then with a synthetic reference; then a double click on "Yes, the money was sent".
- **Expected:**
  - a reference is required;
  - the confirmation says the app sends no money;
  - one completion;
  - the customer's notice is sent through the fake sender.
- **Actual:**
  - "Enter the refund reference from bKash, the bank or the gateway (3 to 150 characters).";
  - the confirmation contains "This does not send money";
  - the history shows "Sent" with the reference;
  - exactly one `refund.completed` audit record;
  - the notice `REFUND_COMPLETED` has status `SENT` with provider `fake`. (UI, DB)

**R-05: server-side boundaries after completion**

- **Expected:** a second payout is refused; a customer token can't list or decide refunds.
- **Actual:**
  - a second completion through the API (Admin Bravo's token) gives **409 `REFUND_NOT_APPROVED`**;
  - a customer token gets **403** on `GET /admin/refunds` and **403** on `POST /admin/refunds/{id}/decision`. (API)

**R-06: a partial request from the order page**

- **Preconditions:** customer One pays a second online order.
- **Steps:** Alpha opens the admin order page, then "Request refund". An amount above what's left is entered, then 100. Then a double click on "Yes, request the refund".
- **Expected:** the over-limit amount is refused; the confirmation says another admin must approve; one request of 100.00.
- **Actual:**
  - "The amount must be more than 0 and at most what is left to refund.";
  - the confirmation contains "Another admin must approve";
  - exactly one REQUESTED refund of 100.00. (UI, API)

**R-07: the requester can't approve**

- **Expected:** on the requester's own card, Approve is disabled with an explanation, and the API refuses the self-approval independently.
- **Actual:**
  - the card says "You requested this refund, so another admin must approve it. You can withdraw it.";
  - Approve is disabled;
  - `POST /admin/refunds/{id}/decision` with `APPROVE` and Alpha's own token gives **409 `SECOND_APPROVER_REQUIRED`**. (UI, API)

**R-08: withdrawing**

- **Actual:** the banner reads "Your refund request for order … is withdrawn."; the history row shows "Withdrawn"; one `refund.rejected` audit record. (UI, DB)

**R-09: a second admin approves**

- **Steps:** Alpha requests a full refund. Bravo approves it in the UI.
- **Actual:** Bravo's card reads "another admin"; Approve is enabled; the refund is APPROVED. (UI, API)

**R-10: phone width**

- **Steps:** a 390 × 844 viewport.
- **Actual:** `scrollWidth` 390 ≤ `clientWidth` 390, so there's no sideways scrolling. (UI)

### 5.2 Disabled account

All disable and enable actions used the operator command, authorized by Admin Alpha's credentials from a secret file.

**D-01: signed out on the next click**

- **Preconditions:** customer Three is signed in with the app open.
- **Steps:** disable the account with the command, then click "My orders" in the already-open app.
- **Expected:** the user lands on `/login`.
- **Actual:**
  - the command printed "Disabled account <id> and revoked 3 of its sessions";
  - the click led to `/login` with the return address kept. (UI, LOG)
- **About the first attempt:** it was recorded FAIL, but **the cause was the test script.** It did a full page load before clicking. That load runs the session-restore refresh, which is correctly refused for a disabled account, so the user was already signed out and the link it meant to click wasn't there. The rerun clicks inside the loaded app, as the scenario intends.

**D-02: the old token, the sessions and the audit**

- **Expected:**
  - the access token issued before the disable gets 401 with the generic detail;
  - no live refresh token remains;
  - an audit record names the admin and the reason.
- **Actual:**
  - **401 `INVALID_TOKEN`**, detail "The access token is invalid or has expired.";
  - 0 unrevoked refresh tokens, revoke reason `ACCOUNT_DISABLED`;
  - `account.disabled` with actor type ADMIN, actor Alpha and the reason text;
  - the audit details contain no phone number. (API, DB)

**D-03: sign-in refused**

- **Actual:** the user stays on `/login` with "The phone, email or password is incorrect.", the same message as a wrong password, so it doesn't reveal that the account is disabled. (UI)

**D-04: re-enabling**

- **Expected:** after the enable, sign-in works, and no refresh token from before the disable is usable.
- **Actual:**
  - the enable succeeded;
  - sign-in and `/orders` work;
  - all 3 refresh tokens issued before the disable are revoked with `ACCOUNT_DISABLED`;
  - the only tokens left are the new sign-in's: one already used by the next page load's refresh, and one live;
  - the audit reads `account.disabled`, `account.enabled`. (UI, DB)
- **About the two FAIL entries:**
  - **The first attempt** failed when its request landed in the 12-minute environment freeze (section 2).
  - **The rerun's automated assertion** counted "not revoked" tokens. That also counts the token the page load had just rotated, which is marked used, not revoked. It reported 2 where the correct count of live tokens (neither used nor revoked) is 1.
  - The verdict comes from the database rows listed above. **Both failures were in the test, not the application.**

**D-05: the last enabled admin**

- **Steps:** disable Bravo (succeeds). Then try to disable Alpha, now the only enabled admin. Then re-enable Bravo.
- **Expected:** refused; the refusal is audited; the number of enabled admins doesn't drop to 0.
- **Actual:**
  - the command exited 1 with "This is the last enabled ADMIN; provision another before disabling it.";
  - enabled admins stayed at 1;
  - audit `account.status_change_refused` with reason `LAST_ENABLED_ADMIN`;
  - Bravo was re-enabled. (LOG, DB)

**D-06: a wrong provisioner password**

- **Actual:**
  - exit 1 with "The provisioner could not be authenticated as an enabled ADMIN.";
  - the target stayed enabled;
  - audit SYSTEM `PROVISIONER_BAD_PASSWORD`;
  - the password appears nowhere in the output. (LOG, DB)

**D-07: a pre-disable access token after a quick re-enable (FAIL)**

- **Steps:** customer Three signs in through the API (access token T). Disable the account, then re-enable it, 0.6 minutes apart. Call `GET /orders` with T before, during and after.
- **Expected** (from ADR-0029: "a token stolen before the disable stays dead"): T refused after the re-enable.
- **Actual:** **200**, then **401** while disabled, then **200** after the re-enable. (API)
- **Reproduction:**
  1. Sign in and keep the access token.
  2. Disable the account with the command.
  3. Re-enable it with the command within 15 minutes of the token's issue.
  4. Call any customer endpoint with the token: 200.
- **Finding:** F-1.

### 5.3 Demotion

Admin Bravo's role was set to CUSTOMER in the throwaway database while Bravo had the refunds page open, then restored to ADMIN afterwards.

**M-01: the API refuses at once**

- **Actual:**
  - Bravo's existing token gets **403 `FORBIDDEN`** on `GET /admin/refunds`;
  - a payout on an approved refund gets **403**, and the refund stays APPROVED (DB);
  - `GET /orders` still gives **200** (customer access kept). (API, DB)

**M-02: the open admin pages before a refresh (observation)**

- **The screen:**
  - the header still shows "Dashboard";
  - the refund cards still render;
  - "Record refund sent" gives "You do not have permission to perform this action." and nothing changes.
- **Navigation:** the header's Dashboard link still opens `/admin`, because the browser's route guard uses the session's role.
- **The dashboard:** it shows no tiles and the message "We could not load the dashboard. Please try again in a moment." (UI)
- This matches the ADR-0028 correction (menus visible until the next refresh). The wording is finding F-2.
- The first attempt stopped at a test-script selector ambiguity (the page has two "Dashboard" links); the observation was rerun.

**M-03: after a reload**

- **Actual:** after one reload (one refresh), the header shows no admin links, and `/admin` sends the user to `/`. (UI)

### 5.4 Production readiness check

**P-01**

- **Steps:** `java -jar` the built jar with `SPRING_PROFILES_ACTIVE=prod`, pointed at the throwaway database, with these left in place:
  - the shipped development defaults (localhost origins and return URLs, a relative proof directory);
  - `allow-ephemeral-key=true` and `secure-cookies=false`;
  - an enabled SSLCommerz gateway on a sandbox-style `.invalid` address.
- **Expected:** startup refused; one message naming every setting and its environment variable; no values; no web server.
- **Actual:**
  - exit 1, and no web server started;
  - one message listing all 7 rules: `allowed-origins`, `secure-cookies`, `allow-ephemeral-key`, `return-url`, `api-base-url`, `sslcommerz.base-url`, `proof-storage.directory`;
  - no localhost URL, proof path, sandbox host or dummy secret in the message;
  - the dummy SMS token and store password appear nowhere in the whole log. (LOG)
- **Observed order:** Flyway ran against the database (17:19:55) **before** the refusal (17:20:05). See F-3.

## 6. Findings

Ranked by severity. **Verified** means reproduced in this run; **inferred** means reasoned from code or behaviour, not reproduced.

### Critical, High, Medium

None.

### Low

**F-1: re-enabling an account revives its unexpired access tokens.** Verified (D-07).

- **Effect:** for up to 15 minutes after issue, an access token from before a disable is accepted again once the account is re-enabled. Refresh tokens are not affected: they stay revoked (D-04), so no session continues beyond the access token's remaining life.
- **Why Low:** exploiting it needs a stolen access token plus a re-enable within that token's life, and the window is at most 15 minutes.
- **It contradicts ADR-0029** ("a token stolen before the disable stays dead"), which is accurate only for refresh tokens.
- **Possible fix** (inferred, not designed): store a "valid from" time per account, set on disable and enable, and refuse access tokens issued before it in `AccountStateJwtConverter`. Or reword ADR-0029 to state the 15-minute limit.

**F-2: after a demotion, admin pages blame a temporary fault.** Verified (M-02).

- **Effect:** until the next refresh (at most 15 minutes) the admin pages stay reachable. When it loads data, the dashboard says "We could not load the dashboard. Please try again in a moment." (verified). That suggests a temporary error rather than lost access. The refunds page's own load-error text ("could not load the refunds") is generic in the same way; that comes from its template and wasn't observed during the demotion (inferred).
- **Why Low:** the API refuses every action correctly (M-01), so the issue is wording and confusion only.
- **Possible fix:** on a 403 from an admin endpoint, refresh the session (which updates the role and hides the menus) and say that the account no longer has admin access.

**F-3: under `prod`, database migrations run before the readiness refusal.** The order is verified (P-01); the consequence is inferred.

- **Effect:** in this run Flyway validated the database 10 seconds before the readiness check refused to start. With a pending migration, a misconfigured production deploy would apply it, then refuse to start.
- **Why Low:** migrations are forward-only and would be applied by the next correct start anyway. It matters for rollback planning: a refused release may already have changed the schema.
- **Possible fix:** run the readiness check before Flyway, for example as an early startup listener, or document the order in `docs/launch-readiness.md`.

**F-4: ADR wording.** Verified (D-07, M-02).

- ADR-0029's "a token stolen before the disable stays dead" needs a qualifier (F-1).
- ADR-0028's correction is confirmed, but it doesn't mention the misleading message (F-2).
- Documentation only.

### Observations (not defects)

- **The login rate limit is per address** (10 per 5 minutes). It stopped the QA script once after this session's earlier sign-ins.
  - In a shop where staff and customers share one public address, several sign-ins in a few minutes could hit it.
  - This is an inference about operations, not a defect; noted for the launch checklist.
- **Test-script defects** caused the first R-02, D-01, D-04 and M-02 records. They're shown in the matrix notes and section 5, not counted as product failures.

## 7. Security and authorization observations

- **Server-side enforcement is independent of the screen.** Every boundary tested through the API held:
  - self-approval: 409 `SECOND_APPROVER_REQUIRED`;
  - customers on admin refund endpoints: 403;
  - a second payout: 409;
  - a demoted admin: 403;
  - a disabled account: 401.
- **Duplicate submissions:** double clicks on approve, payout and request each produced one record (audit counts in R-03, R-04, R-06).
- **Audit records:**
  - every refund step was audited once;
  - disable, enable and the refused status changes were audited, with actor, reason code and no phone number or password;
  - a refused provisioner is recorded as SYSTEM with `PROVISIONER_BAD_PASSWORD`.
- **No enumeration:**
  - a disabled account's sign-in gets the same message as a wrong password;
  - a disabled account's API call gets the generic invalid-token detail;
  - the command's provisioner refusal is the same for every cause.
- **No secret exposure:** none of these appeared in the command output, the API responses or the `prod` startup log:
  - passwords;
  - the dummy provider token;
  - the store password;
  - full phone numbers.
- **Known residual risk confirmed:** F-1. **Not verified here:** cookie `Secure` behaviour behind HTTPS (local runs are plain HTTP).

## 8. Blocked or unverified scenarios

- **Blocked:** none.
- **Not verified here, by scope:**
  - real SMS and payment providers;
  - real Safari and Android;
  - production HTTPS, cookies and same-origin hosting;
  - the full checkout, payment and reconciliation regression.
- **Rerun because of test-script defects:** R-02, D-01, D-04, M-02. D-04's first attempt was also affected by the environment freeze. Each final verdict comes from a rerun or from database evidence, as described in section 5.

## 9. Release-readiness implications

- **No release blocker** from this verification. The refund screen, disabling accounts, demotion handling and the production readiness check behave as their ADRs describe, apart from F-1's 15-minute window.
- **F-1 is worth fixing before relying on disable-then-re-enable as an incident response.** Until it's fixed, operators should wait at least 15 minutes after a disable before re-enabling an account they suspect was compromised. Rotating the password (by re-provisioning) remains outside the app.
- **F-3 belongs in release planning:** treat a refused `prod` start as possibly having migrated the database.
- The launch items in `docs/launch-readiness.md` are unchanged by this report.

## 10. Recommended follow-up tasks

Each is a separate task that needs its own approval.

1. **F-1:** refuse access tokens issued before an account's last status change (a "valid from" time per account, checked in `AccountStateJwtConverter`), with a regression test based on D-07. Alternatively, reword ADR-0029 and the README to state the 15-minute limit.
2. **F-2:** on a 403 from an admin endpoint, refresh the session and show "Your account no longer has admin access" instead of the generic retry message, with a frontend test.
3. **F-3:** run `ProductionReadinessCheck` before Flyway, or document the order in the launch checklist and the deploy notes.
4. **F-4:** amend ADR-0029 (and the ADR-0028 note) once the F-1 and F-2 decisions are made.
5. **Observation:** add a launch-checklist note about the per-address login limit when staff share one public IP address.
