# ADR-0014: v1.1 order detail and customer/admin DTO split

- Status: Accepted
- Date: 2026-11-16

## Context
The v1.0 `Order` schema had totals and status only; customers could not see what they ordered, and admin fulfillment views could not be built.

## Decision
- Add `OrderDetail` (`allOf: [Order, …]`, a strict superset) with items, customization snapshots, quantities, unit prices, line totals, delivery address/zone/slot, payment method/status summary, order status, timestamps, hold expiry, cancellation deadline, `cancellable`, and customer-safe status history (status + time only).
- `GET /orders/{orderId}`, `POST /orders` and `POST /orders/{orderId}/cancel` return `OrderDetail`. List endpoints keep returning `Order` (now with optional `paymentMethod`, `createdAt`).
- `AdminOrderDetail` (`allOf: [OrderDetail, …]`) adds customer identity, full status events with actors and reasons, payment records (including provider transaction id and verification notes) and refunds, via `GET /admin/orders/{orderId}`.
- Customer DTOs never contain gateway session ids/secrets, provider transaction ids, verification notes, admin reasons or actor identities. Backend uses distinct customer and admin DTO classes; JPA entities are never serialized.

## Consequences
- Backward-compatible: no v1.0 field, operation or required property removed (validated by script; see Phase 1 CI check).
- Tests assert customer responses do not contain admin-only fields.
