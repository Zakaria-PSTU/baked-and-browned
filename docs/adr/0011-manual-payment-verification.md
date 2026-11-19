# ADR-0011: COD and admin-verified manual payments

- Status: Accepted (refund rules amended by ADR-0017)
- Date: 2026-11-16

## Context
Customers in Dhaka commonly pay cash on delivery or by bank / mobile financial service transfer. Customer-supplied evidence (screenshots, references) can be forged.

## Decision
- COD: order CONFIRMED at placement; payment PENDING until an ADMIN records the collection (`adminVerifyPayment` APPROVE), then SUCCEEDED.
- MANUAL: order PENDING_PAYMENT. The customer may submit provider (BANK_TRANSFER, BKASH, NAGAD, ROCKET, OTHER), transaction reference and an optional proof file (`submitManualPayment`). Submission never changes payment status.
- An ADMIN independently checks the bank/MFS statement and APPROVEs (payment SUCCEEDED, order CONFIRMED) or REJECTs (payment FAILED, order CANCELLED, holds released). A note is mandatory; the decision is stored on the payment (`verified_by`, `verified_at`, `verification_note`) and in `audit_events`.
- A transaction reference cannot back two payments unless the earlier submission was rejected (unique index).
- Proof files: JPEG/PNG/PDF, ≤ 5 MB, validated by content, stored in private storage outside the web root, downloadable by ADMIN only as an attachment.
- Refunds: REQUESTED → APPROVED/REJECTED → COMPLETED, each step by an ADMIN with reason/reference, audited. Payment becomes REFUNDED only on COMPLETED. Full refunds only in MVP.

## Business configuration still required before launch
- Which bank accounts / MFS merchant numbers are shown to customers (business configuration; how: ADR-0022).
- Proof-file retention period.

## Consequences
- V5 migration (`manual_payment_submissions`, `refunds`, payment verification columns).
- Tests: submission leaves payment PENDING, approve/reject paths, duplicate reference, non-admin verification (403), oversize/forged-type upload.
