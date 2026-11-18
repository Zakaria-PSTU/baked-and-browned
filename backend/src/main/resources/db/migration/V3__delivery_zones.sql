CREATE TABLE delivery_zones (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 code VARCHAR(60) NOT NULL UNIQUE CHECK (code ~ '^[A-Z0-9_]{2,60}$'),
 name VARCHAR(120) NOT NULL UNIQUE,
 delivery_fee NUMERIC(12,2) CHECK (delivery_fee >= 0),
 min_order_amount NUMERIC(12,2) CHECK (min_order_amount >= 0),
 active BOOLEAN NOT NULL DEFAULT FALSE,
 sort_order INT NOT NULL DEFAULT 0,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 CONSTRAINT ck_zone_active_requires_fee CHECK (NOT active OR delivery_fee IS NOT NULL)
);
CREATE TRIGGER trg_delivery_zones_updated_at BEFORE UPDATE ON delivery_zones
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Reference examples only: inactive, no assumed real fees.
INSERT INTO delivery_zones (code, name, delivery_fee, min_order_amount, active, sort_order) VALUES
 ('DHANMONDI',   'Dhanmondi',   NULL, NULL, FALSE, 10),
 ('MOHAMMADPUR', 'Mohammadpur', NULL, NULL, FALSE, 20),
 ('MIRPUR',      'Mirpur',      NULL, NULL, FALSE, 30),
 ('UTTARA',      'Uttara',      NULL, NULL, FALSE, 40),
 ('GULSHAN',     'Gulshan',     NULL, NULL, FALSE, 50),
 ('BANANI',      'Banani',      NULL, NULL, FALSE, 60);

-- Order snapshot: zone reference; orders.delivery_area holds the zone name
-- and orders.delivery_fee the fee at placement time.
ALTER TABLE orders ADD COLUMN delivery_zone_id UUID NOT NULL REFERENCES delivery_zones(id);
CREATE INDEX idx_orders_zone ON orders(delivery_zone_id);
