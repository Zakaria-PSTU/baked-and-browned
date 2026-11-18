-- Cancellation cutoff per product; strictest cutoff is snapshotted on the order.
ALTER TABLE products
  ADD COLUMN cancellation_cutoff_hours INT NOT NULL DEFAULT 24
    CHECK (cancellation_cutoff_hours BETWEEN 0 AND 720);

-- Product-specific text limit (stricter than the 250-char column).
ALTER TABLE product_customization_groups
  ADD COLUMN text_max_length INT CHECK (text_max_length BETWEEN 1 AND 250);

ALTER TABLE orders
  ADD COLUMN cancellation_deadline TIMESTAMPTZ NOT NULL,
  ADD COLUMN request_hash CHAR(64) NOT NULL;

-- Order numbers: BB-YYYYMMDD-NNNNNN (Dhaka date + this sequence), built by the service.
CREATE SEQUENCE order_number_seq START 1;

CREATE TABLE order_status_history (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 order_id UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
 from_status order_status,
 to_status order_status NOT NULL,
 actor_type actor_type NOT NULL,
 actor_user_id UUID REFERENCES users(id),
 reason VARCHAR(500),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 CHECK (from_status IS DISTINCT FROM to_status),
 CHECK (actor_type NOT IN ('CUSTOMER','ADMIN') OR actor_user_id IS NOT NULL),
 CONSTRAINT ck_admin_cancel_reason
   CHECK (NOT (to_status = 'CANCELLED' AND actor_type = 'ADMIN') OR reason IS NOT NULL)
);
CREATE INDEX idx_status_history_order ON order_status_history(order_id, created_at);
CREATE TRIGGER trg_status_history_append_only
  BEFORE UPDATE ON order_status_history
  FOR EACH ROW EXECUTE FUNCTION reject_mutation();

-- One-time consumption/release of holds.
ALTER TYPE reservation_status ADD VALUE 'CONSUMED';
ALTER TABLE slot_reservations
  ADD COLUMN released_at TIMESTAMPTZ;
ALTER TABLE inventory_reservations
  ADD COLUMN released_at TIMESTAMPTZ,
  ADD COLUMN consumed_at TIMESTAMPTZ,
  ADD CONSTRAINT ck_inv_release_xor_consume CHECK (released_at IS NULL OR consumed_at IS NULL);
CREATE INDEX idx_inventory_reservations_order ON inventory_reservations(order_id);

ALTER TABLE ingredients
  ADD COLUMN low_stock_threshold NUMERIC(14,3) CHECK (low_stock_threshold >= 0);

CREATE TYPE inventory_movement_type AS ENUM ('INITIAL','ADJUSTMENT','CONSUMPTION');
CREATE TABLE inventory_movements (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 ingredient_id UUID NOT NULL REFERENCES ingredients(id),
 movement_type inventory_movement_type NOT NULL,
 quantity_delta NUMERIC(14,3) NOT NULL CHECK (quantity_delta <> 0),
 on_hand_after NUMERIC(14,3) NOT NULL CHECK (on_hand_after >= 0),
 order_id UUID REFERENCES orders(id),
 actor_type actor_type NOT NULL,
 actor_user_id UUID REFERENCES users(id),
 reason VARCHAR(300),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 CHECK (movement_type <> 'CONSUMPTION' OR order_id IS NOT NULL),
 CHECK (movement_type <> 'ADJUSTMENT' OR (reason IS NOT NULL AND actor_user_id IS NOT NULL))
);
CREATE INDEX idx_inventory_movements_ingredient ON inventory_movements(ingredient_id, created_at DESC);
CREATE UNIQUE INDEX uq_inventory_consumption_once
  ON inventory_movements(order_id, ingredient_id) WHERE movement_type = 'CONSUMPTION';
CREATE TRIGGER trg_inventory_movements_append_only
  BEFORE UPDATE OR DELETE ON inventory_movements
  FOR EACH ROW EXECUTE FUNCTION reject_mutation();
