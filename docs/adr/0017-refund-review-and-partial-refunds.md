# ADR-0017: Second-approver refund review and partial refunds

- Status: Accepted
- Date: 2026-11-18
- Amends: ADR-0011 (refunds were full and single-step-approved)

## Context
Refunds move money out of the business. ADR-0011 allowed the ADMIN who opened a refund to approve it too, and only full refunds. The business wants every refund reviewed by a second administrator, partial refunds (for example one missing box), and the customer told when the money has been sent.

## Decision
- Workflow unchanged in shape: REQUESTED → APPROVED/REJECTED (note required) → COMPLETED (external reference required). Every step is audited.
- Second approver: the account that requested a refund (an ADMIN, or an ADMIN who placed and cancelled their own order) may REJECT (withdraw) it but never APPROVE it; another ADMIN must. Enforced by the service (409 `SECOND_APPROVER_REQUIRED`) and by the database (`ck_refund_second_approver`). Customer-requested refunds (from cancellations) always need an ADMIN approval.
- Each refund step requires the acting account to be an enabled ADMIN in the database at that moment, not only an ADMIN token, so a demoted or disabled ADMIN's unexpired token cannot approve or complete refunds.
- Partial refunds: a request may name an amount (> 0, at most what is left); without one it covers everything not yet refunded, and cancellations request only the rest. At most one refund per payment is open (REQUESTED or APPROVED) at a time (`uq_refund_open_per_payment`).
- Cap: non-rejected refunds of a payment never exceed its amount. The service checks under the payment lock; a database trigger (`ck_refund_within_payment`) locks the payment row and refuses any insert or update that would exceed it, also for writes that bypass the service.
- The payment becomes REFUNDED only when completed refunds add up to its full amount; a partial refund leaves it SUCCEEDED, with the total exposed as `AdminPayment.refundedAmount` (API v1.2, additive).
- Completing a refund publishes `refund.completed`; the customer receives an SMS with the amount and order number (ADR-0016).

## Consequences
- V7 migration (`V7__refund_policy.sql`).
- A bakery with a single ADMIN account cannot approve ADMIN-requested refunds; a second ADMIN account is required before launch.
