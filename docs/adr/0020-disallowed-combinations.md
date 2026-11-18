# ADR-0020: Disallowed option combinations per product

- Status: Accepted
- Date: 2026-11-18
- Builds on: business-rules (Customization), ADR-0019

## Context
Some cake options cannot go together: a fondant finish with fresh fruit, or two fillings that clash. The business rules deferred these "disallowed combinations" to the custom-cake phase. The owner chose to define them per product (2026-11-18), because the same two options may clash on one cake and not on another.

## Decision
- **Storage:** `product_disallowed_option_pairs (product_id, option_a, option_b)`.
  - Each pair is stored once: `option_a < option_b` in PostgreSQL's `uuid` order, which is the canonical text order. The service sorts by that, not by `UUID.compareTo`.
  - The pair references the product and the options, not the product's allowed-option rows. Replacing a product's customizations rewrites those rows, so a cascade from them would silently wipe every pair. Instead, that replacement deletes only the pairs whose options the product no longer allows, and the audit records how many.
- **Rule:** a pair names two different options the product currently allows. They may be in the same group (two clashing toppings) or in different groups.
- **Validation:** a cart line choosing both options of a pair is rejected at quote and checkout with 422 `DISALLOWED_COMBINATION`, naming both options.
- **Admin API:** `GET`/`PUT /admin/products/{productId}/disallowed-combinations` replaces the whole list under the product row lock. Invalid input is 422 `INVALID_COMBINATION`, and nothing changes. Changes are audited.
- **Public API:** `Product.disallowedCombinations` lists the pairs whose options are both active, so the web app can disable a clashing choice before checkout. The server check stays authoritative.
- **Out of scope:** only pairs are modelled. Rules such as "requires" or three-way clashes would need a new ADR.

## Consequences
- Pair checks are linear in the number of pairs per product, which is fine at bakery scale.
- Deactivating an option keeps its pairs. They come back into force if the option is reactivated.
