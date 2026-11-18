# ADR-0018: SSLCommerz adapter from the public v4 docs

- Status: Proposed (pending sandbox confirmation and owner approval of the high-risk rule)
- Date: 2026-11-18
- Builds on: ADR-0008, ADR-0012

## Context
Phase 3B implements the SSLCommerz adapter behind the `PaymentGateway` port before a merchant or sandbox account exists. It follows the public v4 developer docs (developer.sslcommerz.com/doc/v4), read on 2026-11-18. The docs leave several points open:
- How `verify_sign` is computed.
- Whether the browser comes back to `success_url`/`fail_url`/`cancel_url` by GET or by form POST.
- Whether `ipn_url` per request or only the merchant panel setting applies.
- Whether `tran_id` may be 30 or 50 characters, and from which characters.
- Whether `cus_add1` is mandatory.

## Decision
- **Session:** a form POST to `/gwprocess/v4/api.php` with every field the docs mark mandatory.
  - Our session id is the `tran_id` we choose: `BB` plus the payment id in base 36, at most 27 characters of `[0-9a-z]`.
  - The order number goes in `value_a` and comes back as the order reference.
  - `shipping_method=NO` (own delivery); `product_profile=physical-goods`.
- **Customer details:** SSLCommerz requires an email, city, postcode and country.
  - Accounts without an email send a configured store address (`fallback-customer-email`).
  - City, postcode and country are configured (Dhaka, 1000, Bangladesh).
- **Authenticity:** the IPN is never trusted and `verify_sign` is not used, because its algorithm is undocumented.
  - A `VALID`/`VALIDATED` notice is resolved with the Order Validation API by `val_id`, and the validated `tran_id` must equal the notice's.
  - Any other notice is resolved with the Transaction Query API by `tran_id`.
  - Forged notices therefore cost one outbound call each, so the webhook is rate limited per IP.
- **Status mapping:**
  - `VALID`/`VALIDATED` → SUCCEEDED
  - `PENDING`/`UNATTEMPTED` → PENDING
  - `CANCELLED`/`EXPIRED` → CANCELLED
  - anything else → FAILED
  - When a transaction has several attempts, a success wins.
- **Amount check:** in the currency requested, using `currency_type`/`currency_amount` when present (the docs warn `amount` is converted to BDT), otherwise `currency`/`amount`.
- **High risk:** `risk_level=1` (the docs advise holding the service).
  - The money is recorded (payment SUCCEEDED, `captured_at`), but the order is not confirmed.
  - An AMBIGUOUS_STATUS case with reason `HIGH_RISK` waits for an ADMIN.
- **Browser return:** a public, inert `GET|POST /api/v1/payments/{orderId}/return` answers 303 to the web app's order page. It reads nothing and changes nothing.
- **Activation and secrets:**
  - The adapter is active only with `app.payments.sslcommerz.enabled=true`.
  - Credentials come only from the environment, and startup fails if any setting is missing.
  - The store password is sent only to SSLCommerz, and never logged or included in error messages. The query APIs carry it as a URL parameter, as documented.
- **Tests:** the shared gateway contract and adapter tests run against a local stub of the documented endpoints. No dependency was added.

## Consequences
- Before going live, rerun the contract suite against the sandbox, and confirm:
  - session field acceptance, including `product_profile` and `cus_add2`
  - the `APIConnect` value `DONE`
  - what the Transaction Query API returns for a never-attempted session
  - GET vs POST on the return URLs
  - that the panel IPN URL matches `ipn-url`
- High-risk payments leave the order PENDING_PAYMENT, so hold expiry releases its slot and stock. Reinstating or refunding is an ADMIN reconciliation action (Phase 3C).
- Refunds through the SSLCommerz refund API are out of scope; refunds stay a recorded manual workflow (ADR-0017).
