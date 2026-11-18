# ADR-0021: Per-product display order of customization groups and options

- Status: Accepted
- Date: 2026-11-18
- Builds on: business-rules (Customization), ADR-0015, ADR-0019, ADR-0020

## Context
A cake is chosen in a natural order: size, flavor, frosting, toppings, then the message. Until now a product showed its groups sorted by `code` and each group's options sorted by `name`. For a cake that gave Flavor, Frosting, Message, Size, Toppings, with sizes listed as "1 kg, 2 kg, 500 g". Admins had no way to change that order. Order snapshots were also read back sorted by group name, so a placed order listed its choices in yet another order.

## Decision
- **The admin's list order is the display order.** `PUT /admin/products/{productId}/customizations` already takes an ordered array of assignments, each with an ordered `allowedOptionIds`. The array position becomes the group's order on that product, and the position in `allowedOptionIds` becomes the option's order. The contract is unchanged; only the meaning of the order is now documented.
- **Per product, not per group.** `product_customization_groups.display_order` and `product_customization_options.display_order` (V12), each `INT NOT NULL DEFAULT 0`. A group shared by a brownie box and a cake can be ordered differently on each.
- **Readers** (public and admin product, and the rules used at quote and checkout) sort by `display_order`, then by the previous keys. Ties, including rows written before V12 or by direct SQL, keep the old order.
- **Snapshots keep it.** `order_item_customizations.display_order` stores each choice's position when the order is placed, and order detail reads it back in that order. Rows from before V12 stay 0 and keep their old group-name order. A placed order never moves when the catalog is reordered.
- **Backfill:** V12 numbers existing assignments by code and options by name, so no live product changes how it looks.

## Consequences
- Reordering is a full replacement of the product's assignments, which is the existing write model. There is no separate "move up" endpoint.
- Error checks run in the same order, so a cart with several problems reports the first one in display order.
- Considered and rejected: a global `sort_order` on groups and options. It is simpler, but it would force one order on every product that shares a group.
