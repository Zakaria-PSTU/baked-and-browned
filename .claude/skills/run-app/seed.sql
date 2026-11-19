WITH c AS (
  INSERT INTO categories (name, slug, sort_order, active) VALUES ('Brownies', 'brownies', 1, true) RETURNING id
), p AS (
  INSERT INTO products (category_id, name, slug, base_price, status)
  SELECT id, 'Fudge Brownie Box', 'fudge-box', 1250.00, 'ACTIVE'::product_status FROM c RETURNING id
), i AS (
  INSERT INTO ingredients (name, unit, on_hand) VALUES ('Chocolate', 'g', 100000) RETURNING id
)
INSERT INTO product_ingredients (product_id, ingredient_id, quantity_per_unit) SELECT p.id, i.id, 100 FROM p, i;

UPDATE delivery_zones SET delivery_fee = 60.00, min_order_amount = NULL, active = true WHERE code = 'DHANMONDI';

INSERT INTO delivery_slots (start_at, end_at, capacity_units, reserved_units, active)
SELECT (d + time '10:00') AT TIME ZONE 'Asia/Dhaka', (d + time '12:00') AT TIME ZONE 'Asia/Dhaka', 50, 0, true
FROM (SELECT ((now() AT TIME ZONE 'Asia/Dhaka')::date + 2)::timestamp AS d) x;
