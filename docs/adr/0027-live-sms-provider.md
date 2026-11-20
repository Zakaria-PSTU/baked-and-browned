# ADR-0027: Live SMS provider (SSL Wireless ISMS Plus)

- Status: Proposed
- Date: 2026-11-20
- Related: ADR-0016 (SmsSender port, notices from the outbox), ADR-0003 (outbox), ADR-0006 (no external calls inside transactions), ADR-0012 (SSLCommerz adapter pattern), ADR-0015 (migrations from `docs/database.md`)

## Context
1. **No production sender (NEW-2, launch blocker).** ADR-0016 defined the `SmsSender` port and a `FakeSmsSender` for dev/test only. A `prod` context has no `SmsSender` and refuses to start, by design.
2. **How notices are sent today** (`OrderNotificationDelivery`, V6):
   - one `notifications` row per outbox event and channel;
   - recipient and text are fixed at the first attempt;
   - the row id is the idempotency key, and a SENT notice is never sent again;
   - delivery is at-least-once: a crash between the provider accepting a message and the row being marked SENT resends it once.
3. **Failures never end.** The outbox retries a failed event with backoff up to 1 hour, **indefinitely**, and a notice is only PENDING or SENT:
   - a message the provider will never accept (an invalid or blocked number) is retried every hour forever;
   - a notice that kept failing for a day would still be sent, for example "out for delivery" after the order was delivered.
4. **Regulation in Bangladesh** (from provider and carrier documentation, November 2026; not from BTRC primary sources):
   - application-to-person SMS must go through a sender enlisted with BTRC, which cancelled 41 aggregator licences in early 2025 and 22 enlistments later;
   - branded alphanumeric sender IDs ("masking") must be registered with Grameenphone, Robi and Teletalk, and unregistered IDs have been blocked since 8 September 2025; registration takes about 15 working days to 3 weeks, and IDs are at most 11 characters with no special characters;
   - recipients cannot reply on these routes.
5. **Provider comparison** (November 2026; local aggregators Alpha SMS, BulkSMSBD and MiMSMS; international Twilio and Infobip). SSL Wireless ISMS Plus was chosen because its published API ([documentation](https://ismsplus.sslwireless.com/api-documentation)) fits the existing design best:
   - **duplicate detection:** every message carries a client reference, `csms_id`, and a reused reference is reported as a duplicate, which matches the at-least-once resend;
   - **error codes** separate permanent errors (invalid or blocked number, unpermitted sender ID, invalid message) from temporary ones (rate limit, throughput limit, internal error);
   - **https only**, JSON in and out.

   Its costs: requests must come from an **IP address whitelisted** in the ISMS Plus portal, and prices are not published.

## Decision

### 1. Provider and adapter
- **Provider:** SSL Wireless ISMS Plus, single-SMS endpoint `POST {base-url}/send-sms` with a JSON body. The default base URL is `https://smsplus.sslwireless.com/api/v3`.
- **Request fields:**
  - `api_token`
  - `sid` (sender ID)
  - `msisdn`: our `+8801XXXXXXXXX` without the `+`
  - `sms`: the notice text
  - `csms_id` (below)
- **New package** `com.bakedandbrowned.notification.sms.sslwireless`, built like the SSLCommerz adapter:
  - `SslWirelessProperties`, under `app.notifications.sms.ssl-wireless.*`: `enabled`, `base-url`, `api-token`, `sender-id`, `connect-timeout`, `read-timeout`;
  - a configuration class active only with `enabled=true`, which checks every setting at startup;
  - `SslWirelessSmsSender`, using Spring's `RestClient` with both timeouts.
- **Settings checks:**
  - the base URL must be `https` (plain `http` only for `localhost`, for the test stub);
  - the API token comes only from the environment or a secret store (`APP_NOTIFICATIONS_SMS_SSL_WIRELESS_API_TOKEN`) and is never logged or printed;
  - a missing or invalid setting stops startup.
- **Startup in production is unchanged:** `prod` without an enabled adapter still has no `SmsSender` and fails at startup. Exactly one `SmsSender` exists in any context.
- **What the adapter never logs:** message text, full phone numbers or the token. It logs the masked number (`SmsMessage.toString`), the `csms_id` and the provider's status code only.
- **Provider name** recorded on sent notices: `ssl-wireless`. The provider message id is the response's `reference_id`.

### 2. Client reference (`csms_id`)
- The documented maximum is 20 characters, and the notice id is a 36-character UUID. So `csms_id` is the **first 20 hexadecimal characters of the notice id without hyphens**: deterministic, so a resend after a crash carries the same reference.
- These 20 characters hold about 76 random bits, so an accidental collision is negligible at this shop's volume.
- If SSL Wireless says `csms_id` may not contain letters, the fallback is a new `provider_reference` column holding a numeric value drawn once per notice (an amendment to this ADR before implementation).

### 3. Outcomes
Every response is classified by its `status_code`, and for a single message also by `smsinfo[0].sms_status`.

| Result | Examples | Notice | Outbox event |
|---|---|---|---|
| **Accepted** | `status_code` 200 with `sms_status` SUCCESS | SENT | processed |
| **Already accepted** | duplicate `csms_id` (4023, or `sms_status` DUPLICATE) after a resend | SENT, with the `csms_id` as provider message id | processed |
| **Permanent rejection** | invalid or blocked number (4025, 4026), invalid message (4027, 4028, 4032), duplicate number in request (4024), invalid reference (4020) | **FAILED**, with the code | processed (no retry) |
| **Our configuration is wrong** | unauthorized (4001), sender ID not permitted (4002), IP not whitelisted (4003), bad request format or endpoint (4004, 4005, 4022), signature (4035) | stays PENDING; logged at ERROR | retried with backoff (fixing the configuration must not lose notices) |
| **Temporary** | rate or throughput limit (4029, 4031), internal error (5000), any HTTP 5xx, timeout, connection failure, an unknown code (for example a low balance, which the documentation does not list) | stays PENDING | retried with backoff |

- **Ambiguous outcomes** (timeout or unreadable response) are retried with the same `csms_id`. If the first attempt did get through, the provider reports a duplicate, which is recorded as SENT. There is no double send.
- **A new exception**, `SmsRejectedException`, marks a permanent rejection. `OrderNotificationDelivery` records FAILED and returns normally, so the outbox event completes. Everything else stays `SmsDeliveryException` and is retried, as today.

### 4. Maximum age
- A notice not sent within **24 hours** of its outbox event's creation is marked **EXPIRED** and not sent, and the outbox event completes.
- The age is measured from the outbox event, not the notice row, so a stalled outbox doesn't reset the clock.
- The check runs before every send attempt.

### 5. Migration V14 (written first in `docs/database.md`, ADR-0015)
- `notification_status` gains `FAILED` and `EXPIRED`.
- `notifications` gains:
  - `failed_at TIMESTAMPTZ`;
  - `failure_code VARCHAR(40)`, holding the provider's status code or `EXPIRED`, never message text or a number.
- New checks:
  - FAILED and EXPIRED require `failed_at` and `failure_code`;
  - SENT still requires `sent_at` and `provider`;
  - only PENDING notices may change.
- No backfill: existing rows are PENDING or SENT.

### 6. Sender ID
- **At launch:** the plain-number sender ID assigned to the account, set as `sender-id`.
- **Later:** a branded ID of at most 11 letters (the name is a business decision), once it's registered. Switching is a configuration change only.

### 7. Opt-out
- Every current notice is transactional: order received, confirmed, dispatched, cancelled and expired, and refund sent. They're sent to the account holder's phone, with **no opt-out**. This settles the open question in ADR-0016.
- Any promotional message would need consent, an opt-out and Bangla text (BTRC), and a new ADR.

### 8. Delivery reports
- Out of scope. SENT means **accepted by the provider**, not delivered to the handset. ISMS Plus documents a `reference_id` for status checks but no status endpoint.

## Prerequisites outside the code (business and operations)
1. An ISMS Plus account under a contract that includes a transactional route, and SSL Wireless's current BTRC enlistment certificate.
2. Production's **fixed outgoing IP address**, whitelisted in the ISMS Plus portal. This constrains hosting: the API must leave through a known address (for example a NAT gateway or a static IP).
3. The API token, in the production secret store.
4. Later, a registered branded sender ID.
5. **To confirm with SSL Wireless before or during implementation:**
   - the allowed characters for `csms_id`, and how long references are remembered for duplicate detection;
   - the accepted `msisdn` format (`8801…` or `01…`);
   - the HTTP status codes returned with errors;
   - the code for insufficient balance;
   - whether a test account or free test messages exist.

## Testing
- **A local stub of the ISMS Plus API** (JDK `HttpServer` on `127.0.0.1`, like `SslCommerzStub`), covering:
  - accepted;
  - duplicate `csms_id`;
  - each permanent code, each configuration code and each temporary code;
  - HTTP 5xx, a slow response past the read timeout, a malformed body and a connection refusal.

  It also checks the exact request: fields, number format, the `csms_id` derived from the notice id, and https enforcement outside `localhost`.
- **Logging:** a captured log contains no message text, no full number and no token.
- **A shared `SmsSender` contract test** for the fake and the adapter.
- **Startup:**
  - `prod` without the adapter still fails;
  - an enabled adapter with a missing or invalid setting fails;
  - the fake and the adapter are never both active.
- **Integration, against PostgreSQL:**
  - FAILED and EXPIRED complete the outbox event and are never retried;
  - a configuration error keeps the notice PENDING and retries;
  - a duplicate after a simulated crash is recorded as SENT once;
  - V14 is checked by `DatabaseMigrationTests` and the spec check.
- **One real message** to the owner's phone, through the production configuration, only with explicit approval and owner-supplied credentials.

## Consequences
- The production web app can start once the account, the fixed IP address and the token exist. Until then it still refuses to start, which is deliberate.
- Undeliverable and stale notices end visibly (FAILED or EXPIRED, with a code) instead of retrying forever. Alerts on FAILED and EXPIRED counts, and on configuration errors logged at ERROR, belong with the monitoring in `architecture.md`.
- Duplicate sends after a crash are prevented by the provider's reference check, as far as its documentation states. Its window for remembering references is unconfirmed.
- The hosting choice must provide a fixed outgoing IP address.
- Changing provider later means another adapter behind the same port; the notice statuses and maximum age stay.
- Costs per message are not published; the ISMS Plus contract sets them.

## Rejected alternatives
- **Alpha SMS (sms.net.bd):** published prices and a simple API with delivery reports, but no client reference. A crash at the wrong moment can send one notice twice. Kept as the fallback if the ISMS Plus contract or IP whitelisting is not workable.
- **BulkSMSBD:** no official API documentation found; third-party packages call it over plain HTTP.
- **Twilio, Infobip:**
  - registration through a letter of authorization;
  - Twilio's own Bangladesh guidelines say the sender ID is not preserved on Grameenphone, Robi or Teletalk;
  - per-message prices conflict between sources.
- **Retrying forever (no FAILED or EXPIRED):** endless retries for numbers that can never receive SMS, and late notices that confuse customers.
- **Marking configuration errors FAILED:** a wrong token or a missing whitelist entry would silently drop every notice until noticed.
