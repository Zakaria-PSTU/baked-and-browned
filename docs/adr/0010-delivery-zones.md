# ADR-0010: Database-driven Dhaka delivery zones

- Status: Accepted
- Date: 2026-11-16

## Context
v1.0 stored a free-text `delivery_area` while requiring server-configured fees per area; fees could not be enforced and no endpoint listed areas.

## Decision
- `delivery_zones` table: code, name, delivery fee, minimum order amount (optional, compared to subtotal), active flag, sort order. A zone cannot be active without a fee (DB check).
- Seed Dhanmondi, Mohammadpur, Mirpur, Uttara, Gulshan, Banani as INACTIVE with no fee. No real fees are assumed; an ADMIN configures and activates zones through the admin API before checkout can use them.
- Eligibility: zone active, fee configured, subtotal ≥ minimum order (if set). Otherwise 422.
- The client sends the zone **code** in the existing `DeliveryAddress.area` field (no contract break). Any client-sent fee is ignored.
- Orders snapshot `delivery_zone_id`, zone name (`delivery_area`) and fee (`delivery_fee`).

## Scope note (approved 2026-11-17)
Further eligibility dimensions (per-zone slots, per-zone payment methods) are not modeled; add by a later ADR if needed.

## Consequences
- V3 migration; public `GET /delivery-zones`; admin zone CRUD.
- Tests: inactive zone, zone without fee, below minimum, fee change after order (snapshot unchanged), tampered client fee.
