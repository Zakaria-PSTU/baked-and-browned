# ADR-0019: Customization options carry kitchen costs

- Status: Accepted
- Date: 2026-11-18
- Builds on: business-rules (Customization, Delivery scheduling, Ingredient inventory)

## Context
Custom cakes (Phase 4) vary more than brownie boxes. A larger size takes more oven and decorating time. A hand-painted design needs more notice. Frostings and toppings use stock the product recipe does not include. Until now, capacity units, lead hours and recipes existed only per product, so a cake's options could oversell the kitchen or the stock without anyone noticing.

## Decision
- **Production units:** each option has `extra_production_units` (0 to 100, default 0).
  - A cart line's production units per item are the product's plus every chosen option's.
  - Slot capacity uses that total, and the order item snapshots it.
- **Notice:** each option may have `min_lead_hours` (0 to 720).
  - A line needs the stricter of its product's lead hours and its chosen options'.
  - The 422 `LEAD_TIME_NOT_MET` names whichever is strictest, product or option.
- **Ingredients:** each option may have ingredient lines (`option_ingredients`), per item and added to the product recipe.
  - The lines are the same on every product that offers the option (owner's choice, 2026-11-18).
  - Stock needs, the quote's availability check and checkout reservations include them, in the same ingredient-id lock order.
- **Visibility:** customers see an option's `minLeadHours` on the product; production units and ingredients stay internal.
- **Admin API:** option create and update take `extraProductionUnits` and `minLeadHours` (full replacement: absent means none). New `GET`/`PUT /admin/customization-options/{optionId}/ingredients` mirror the product recipe endpoints, and changes are audited.

## Consequences
- An option shared by a small and a large cake consumes the same extra stock on both. When that is wrong, use separate options, for example "Chocolate frosting (1 kg)".
- Past orders keep their snapshots. Changing an option's costs affects only new quotes and orders.
- Disallowed combinations come next (Phase 4B) and are independent of this ADR.
