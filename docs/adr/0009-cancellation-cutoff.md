# ADR-0009: Per-product cancellation cutoff, strictest wins

- Status: Accepted
- Date: 2026-11-16

## Context
v1.0 allowed CONFIRMED cancellation only "before cutoff and with admin authorization" without defining the cutoff or a customer path.

## Decision
- `products.cancellation_cutoff_hours`, default 24, admin-configurable (0–720).
- At placement the order snapshots `cancellation_deadline = slot start − max(cutoff hours of all items)`. Product changes afterwards do not affect existing orders.
- Customers may cancel their own PENDING_PAYMENT or CONFIRMED order while `now < cancellation_deadline` (`POST /orders/{orderId}/cancel`); otherwise 409.
- ADMIN may cancel any order before OUT_FOR_DELIVERY at any time, with a mandatory reason, recorded in `order_status_history` (DB check enforces the reason) and `audit_events`.
- Cancellation releases unconsumed reservations once. Consumed ingredients are not returned.
- Cancelling an order with a SUCCEEDED payment opens a refund request (REQUESTED); money moves only through the separately approved refund workflow.

## Consequences
- New columns: `products.cancellation_cutoff_hours`, `orders.cancellation_deadline` (V4); `refunds` table (V5).
- API: `cancelMyOrder`, `reason` on `adminChangeOrderStatus`, `cancellationDeadline`/`cancellable` in `OrderDetail`.
- Tests: boundary just before/after the deadline, mixed cutoffs, admin cancel without reason (422), paid-order cancellation creating a refund request.
