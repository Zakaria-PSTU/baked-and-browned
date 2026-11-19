-- Custom cakes (ADR-0021): a product shows its customization groups, and each group's options, in the
-- order the admin assigned them (size before flavor before message), not by code or name. The order
-- is per product, so a group shared by several products may be shown differently on each.
ALTER TABLE product_customization_groups
 ADD COLUMN display_order INT NOT NULL DEFAULT 0 CHECK (display_order >= 0);
ALTER TABLE product_customization_options
 ADD COLUMN display_order INT NOT NULL DEFAULT 0 CHECK (display_order >= 0);

-- Existing products keep the order they were shown in until now (group code, then option name).
UPDATE product_customization_groups pcg
SET display_order = ranked.n
FROM (SELECT pcg2.product_id, pcg2.group_id,
             row_number() OVER (PARTITION BY pcg2.product_id ORDER BY g.code) - 1 AS n
      FROM product_customization_groups pcg2
      JOIN customization_groups g ON g.id = pcg2.group_id) ranked
WHERE pcg.product_id = ranked.product_id AND pcg.group_id = ranked.group_id;

UPDATE product_customization_options pco
SET display_order = ranked.n
FROM (SELECT pco2.product_id, pco2.option_id,
             row_number() OVER (PARTITION BY pco2.product_id, pco2.group_id ORDER BY o.name, o.id) - 1 AS n
      FROM product_customization_options pco2
      JOIN customization_options o ON o.id = pco2.option_id) ranked
WHERE pco.product_id = ranked.product_id AND pco.option_id = ranked.option_id;

-- Order item snapshots keep the order the choices were shown in when the order was placed. Rows from
-- before this migration stay 0 and keep their previous order (group name, then option name).
ALTER TABLE order_item_customizations
 ADD COLUMN display_order INT NOT NULL DEFAULT 0 CHECK (display_order >= 0);
