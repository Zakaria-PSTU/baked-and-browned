# Baked & Browned — Business Rules
Version: 1.1 | Market: Dhaka, Bangladesh | Currency: BDT | Time zone: Asia/Dhaka

> v1.1 incorporates approved decisions ADR-0007 … ADR-0015 (see `docs/adr/`).
> Open items proposed in v1.1 were approved by the owner on 2026-11-17.

## General
- Currency is BDT only. All business dates, cutoffs and "today" calculations use Asia/Dhaka.
- The backend is authoritative for pricing, delivery fees, eligibility, inventory and capacity. Values sent by the browser are display-only and are never trusted.

## Product and catalog
- Categories include Brownies, Cookies, Pastries, Custom Cakes. Admin may add, rename, reorder and deactivate categories through admin APIs.
- Each product has DRAFT/ACTIVE/ARCHIVED status, base price, minimum lead hours, production capacity units and a cancellation cutoff (hours, default 24).
- Only ACTIVE products may be purchased. Archived products remain referenced by historical orders.
- Brownie boxes are the first MVP vertical slice; cakes may require size, flavor, frosting, toppings and a short message. Cake options may cost the kitchen more than their price shows: extra production units, more notice, extra ingredients (ADR-0019).
- Product prices and option price deltas are maintained by ADMIN. Illustrative prices in UI samples are NOT official business prices.

## Customization
- Groups have SINGLE, MULTIPLE or TEXT selection type. Group-level `min_selections`/`max_selections` apply everywhere the group is used; the `required` flag is per product.
- Effective selection rule for a product/group pair:
  - required = true → selections must be between `max(min_selections, 1)` and `max_selections`.
  - required = false → zero selections, or between `max(min_selections, 1)` and `max_selections`.
- Each selectable option must be explicitly allowed for the chosen product, be active, and belong to the stated group.
- Validate minimum/maximum selections, duplicates, inactive options and text length, and that no two chosen options form a pair the product disallows (ADR-0020).
- A TEXT group holds one customer message, maximum 250 characters in the database; a product may set a stricter `text_max_length` (e.g. 50), which the server enforces.
- A product shows its groups, and each group's options, in the order the admin listed them when assigning (ADR-0021); a cake reads size, flavor, frosting, toppings, message. Order snapshots keep that order.
- Never render messages as raw HTML (web, admin views and notifications). Snapshot names, text and price deltas on order items.

## Pricing
`unit_price = base_price + sum(option_deltas)`; `line_total = quantity * unit_price`.
`subtotal = sum(line_total)`; `total = subtotal + delivery_fee - discount`.
- Use BigDecimal and NUMERIC(12,2). All MVP inputs already have 2 decimal places and only addition/multiplication by integers is used, so no rounding occurs; any future rule that divides (e.g. percentage discount) must round HALF_UP to 2 decimals and be documented.
- Discount is always 0.00 in the MVP; discounts require an explicit, audited rule before use.
- `/checkout/quote` is informative; order creation recalculates everything.
- No negative order totals. Base and option prices are nonnegative.

## Delivery zones (ADR-0010)
- Delivery is only to database-configured Dhaka delivery zones. Each zone has a unique code, name, delivery fee, active flag and optional minimum order amount (applied to subtotal).
- A zone is eligible for an order only when it is active, has a configured fee, and the order subtotal meets its minimum order amount (if set).
- Seed data contains Dhanmondi, Mohammadpur, Mirpur, Uttara, Gulshan and Banani as INACTIVE zones with no fee. Admin must configure real fees and activate a zone before checkout can use it. A zone cannot be activated without a fee.
- The client sends the zone code (`DeliveryAddress.area`); the server looks up the fee. Client-provided fees are ignored.
- The order snapshots zone id, zone name and fee; later fee changes do not alter existing orders.
- No further eligibility dimension (e.g. per-zone slot restrictions or COD availability per zone) is modeled in v1.1; adding one requires a new ADR.

## Delivery scheduling
- Delivery slots have start/end instants, capacity units, reserved units, active flag.
- Total required capacity = sum(item quantity × (product production units + the chosen options' extra production units)) (ADR-0019).
- Available capacity = capacity units − reserved units.
- Every line's lead-time requirement must be met relative to the current time and chosen slot start: the product's minimum lead hours, or a chosen option's when that is stricter (ADR-0019).
- Admin can close a slot or reduce future capacity, but never below currently reserved units (409).
- On order placement reserve slot capacity atomically. On cancellation/expiry release once only.
- For future versions, distinguish production workload from courier delivery workload.

## Ingredient inventory
- Each product maps to ingredient consumption quantities in consistent units (managed through admin recipe APIs). A customization option may add its own ingredient lines per item, on top of the product recipe, the same on every product that offers it (ADR-0019).
- `available = on_hand − reserved`.
- Reserve ingredient quantities under row locks at checkout; reject insufficient stock with 409.
- When an order enters PREPARING, consume its reservations exactly once: decrement `on_hand` and `reserved` atomically and mark reservations CONSUMED.
- On cancellation/expiry of an unconsumed order, release reserved only (mark RELEASED, once).
- Every on-hand change (adjustment, consumption) writes an inventory movement with actor and reason.
- Keep quantities nonnegative; reject stock edits that would put `on_hand` below `reserved` (409).
- Wastage, expiry and lot-level inventory are a future extension.

## Payment hold and expiry (ADR-0008)
- ONLINE and MANUAL orders start PENDING_PAYMENT with a 15-minute hold measured from the reservation commit (`orders.expires_at`).
- A scheduled expiry job moves overdue PENDING_PAYMENT orders to EXPIRED and releases slot and ingredient holds. Expiry is idempotent: it locks the order row, re-checks status, and releases each reservation at most once.
- Expiry and payment confirmation lock the same order row, so they serialize; whichever commits first wins.
- MANUAL: once the customer submits a payment reference within the hold, automatic expiry is suspended and the order waits for ADMIN verification. Maximum review window (configurable): until 6 hours after submission or 2 hours before slot start, whichever is earlier; after that the order is expired by the job and, if the payment is later verified, handled as a late payment.
- A gateway success arriving after expiry never auto-confirms. It creates a LATE_SUCCESS reconciliation case; an ADMIN either reinstates the order (only if fresh reservations succeed under locks) or approves a refund. Overselling is never allowed.

## Orders and status transitions
- Initial status: CONFIRMED for COD orders; PENDING_PAYMENT for ONLINE and MANUAL.
- Normal fulfillment: CONFIRMED → PREPARING → READY → OUT_FOR_DELIVERY → DELIVERED.
- PENDING_PAYMENT → CONFIRMED only via verified gateway payment or ADMIN manual-payment verification. The generic admin status endpoint cannot perform this transition.
- PENDING_PAYMENT → EXPIRED (job) or CANCELLED (customer/admin) releases holds.
- Every transition writes `order_status_history` (from, to, actor type, actor id, reason, time). Customers see a customer-safe view (status and time only).
- Preserve product, options, price, delivery zone/fee, address and cancellation-deadline snapshots.
- Each order has a unique human-readable order number (`BB-YYYYMMDD-NNNNNN`, Dhaka date + database sequence) and unique `(user_id, idempotency_key)`.
- Repeated checkout with the same idempotency key and same request body returns the original order (201) and creates nothing new. The same key with a different body is rejected (422, code `IDEMPOTENCY_KEY_REUSED`).
- Customer sees only own orders (non-enumerating 404 otherwise); ADMIN sees all.

## Cancellation (ADR-0009)
- Each product has `cancellation_cutoff_hours` (default 24, configurable by ADMIN).
- At placement, the order snapshots `cancellation_deadline = slot start − max(cutoff hours of all items)` — the strictest cutoff wins for mixed orders. Later product changes do not alter it.
- Customer may cancel their own order while status is PENDING_PAYMENT or CONFIRMED and current time < cancellation deadline. Otherwise 409 (`CANCELLATION_WINDOW_CLOSED` or `INVALID_STATE`).
- ADMIN may cancel any order not yet OUT_FOR_DELIVERY, DELIVERED, CANCELLED or EXPIRED, at any time, but must supply a reason; the change is audited.
- Cancellation releases unconsumed slot and ingredient reservations once. Consumed ingredients are not returned to stock.
- Cancelling an order with a SUCCEEDED payment does not refund automatically; it opens a refund request that follows the refund workflow.

## Payment (ADR-0011, ADR-0012)
- Methods: COD, MANUAL (bank / mobile financial service transfer), ONLINE (hosted gateway). Payment status is separate from order status.
- Every order gets a payment record at placement with status PENDING. `Order.paymentStatus` is the status of the order's current (latest non-superseded) payment record.
- COD: order may be CONFIRMED before collection; payment becomes SUCCEEDED only when an ADMIN records the collection.
- MANUAL: customer may submit provider, transaction reference and optional proof file. Submission never changes payment status. Payment stays PENDING until an ADMIN independently verifies it against the bank/MFS statement (→ SUCCEEDED, order → CONFIRMED) or rejects it (→ FAILED, order → CANCELLED, holds released). Verification and rejection require a note and are audited.
- ONLINE: a hosted-page redirect is not proof of payment. Validate provider event authenticity, transaction ID, order reference, amount, currency and final status server-to-server.
- Deduplicate callbacks using provider/event ID and transaction uniqueness.
- Never call external payment services inside a database transaction.
- Payment failures/expiry release reservations only after a terminal decision; ambiguous outcomes go to reconciliation.
- Refunds (ADR-0017): REQUESTED → APPROVED/REJECTED by ADMIN (note required) → COMPLETED when an ADMIN records the external refund reference. Whoever requested a refund never approves it: a second, enabled ADMIN must. Refunds may be partial; together they never exceed the payment, and only one is open at a time. Payment becomes REFUNDED only when completed refunds equal its amount. The customer is told by SMS when a refund is sent. Every step is audited.
- Initial online gateway candidate: SSLCommerz, subject to merchant onboarding and confirmation of its current API requirements. Tests use a fake gateway.

## Customers and administration
- Customer registration grants CUSTOMER only. ADMIN accounts are provisioned out of band; migrations and seed data never contain credentials.
- Store normalized Bangladeshi phone numbers (+8801XXXXXXXXX), for accounts and delivery contacts.
- Use least-privilege authorization and ownership checks on every endpoint.
- Customer data is used for order fulfillment; avoid collecting unnecessary sensitive information. Manual-payment proof files are visible only to ADMIN.
- All administrative changes (catalog, prices, zones, slots, inventory, order status, payment decisions, refunds) are written to the audit log.

## Notifications
- Send order received, confirmed, dispatched, cancelled and expired notices, and refund-sent notices, via outbox after commit.
- Delivery failures must not roll back paid orders.
- Notifications must be retryable and idempotent.

## Acceptance scenarios
1. Two customers try the last available brownie box: only one order reserves stock.
2. Two customers choose the final capacity unit: only one reservation succeeds.
3. Same customer submits checkout twice with same key: one order exists.
4. Unauthorized customer requests another customer's order: non-enumerating 404.
5. User submits invalid topping ID: 422.
6. Gateway sends duplicate success callbacks: payment confirmed once.
7. Gateway success arrives after hold expiry: reconciliation case opened; no oversell.
8. Product price changes after purchase: prior order snapshots unchanged.
9. COD order confirmed: payment remains PENDING until collection is recorded.
10. Custom cake message contains markup: displayed as escaped text.
11. Unpaid ONLINE order passes 15 minutes: expired once, holds released once, even if the job runs twice concurrently.
12. Customer cancels 25 hours before slot (24 h cutoff): succeeds. At 23 hours: 409. Mixed order with 24 h and 48 h items uses 48 h.
13. Customer submits manual proof: payment stays PENDING; only ADMIN verification confirms the order.
14. Checkout to an inactive zone, a zone without a fee, or below the zone minimum: 422. A client-sent fee is ignored.
15. Admin reduces slot capacity below reserved units, or on-hand below reserved: 409.
16. Same idempotency key with a different cart: 422, no new order.
