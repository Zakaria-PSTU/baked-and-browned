# ADR-0008: 15-minute payment hold and idempotent expiry

- Status: Accepted
- Date: 2026-11-16

## Context
ONLINE and MANUAL orders reserve slot capacity and ingredients before payment is known. Abandoned checkouts must not lock capacity indefinitely, and late gateway successes must never cause overselling.

## Decision
- ONLINE and MANUAL orders start PENDING_PAYMENT with `expires_at = reservation commit time + 15 minutes`. COD orders have no hold expiry.
- A scheduled job claims overdue orders with `FOR UPDATE SKIP LOCKED`, re-checks status under the lock, sets EXPIRED, and releases each slot/inventory reservation at most once (`released_at`, reservation status RELEASED). Re-running the job or running it concurrently has no further effect.
- Payment confirmation locks the same order row, so expiry and confirmation serialize.
- A verified gateway success for an EXPIRED order never auto-confirms. It records the payment as SUCCEEDED and opens a `LATE_SUCCESS` reconciliation case. An ADMIN resolves it by REINSTATE (only if fresh reservations succeed under locks) or REQUEST_REFUND.
- MANUAL: a customer submission within the hold suspends automatic expiry until the admin review window ends.

## Approved default (2026-11-17)
Manual review window: until 6 hours after submission or 2 hours before slot start, whichever is earlier (configurable).

## Consequences
- Requires `reservation_status` values RELEASED/CONSUMED with timestamps (V4) and `payment_reconciliation_cases` (V5).
- Integration tests: concurrent expiry runs, expiry vs confirmation race, late success after expiry, release-once.
- Alerting on open reconciliation cases.
