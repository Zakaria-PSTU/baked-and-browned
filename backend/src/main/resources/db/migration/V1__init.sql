CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TYPE user_role AS ENUM ('CUSTOMER','ADMIN');
CREATE TYPE product_status AS ENUM ('DRAFT','ACTIVE','ARCHIVED');
CREATE TYPE order_status AS ENUM ('PENDING_PAYMENT','CONFIRMED','PREPARING','READY','OUT_FOR_DELIVERY','DELIVERED','CANCELLED','EXPIRED');
CREATE TYPE payment_status AS ENUM ('PENDING','SUCCEEDED','FAILED','CANCELLED','REFUNDED');
CREATE TYPE payment_method AS ENUM ('COD','MANUAL','ONLINE');
CREATE TYPE reservation_status AS ENUM ('HELD','CONFIRMED','RELEASED');

CREATE TABLE users (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 full_name VARCHAR(150) NOT NULL,
 email VARCHAR(255),
 phone VARCHAR(20) NOT NULL UNIQUE,
 password_hash VARCHAR(255) NOT NULL,
 role user_role NOT NULL DEFAULT 'CUSTOMER',
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 CONSTRAINT ck_phone CHECK (phone ~ '^\+8801[3-9][0-9]{8}$')
);
CREATE UNIQUE INDEX uq_users_email_ci ON users(lower(email)) WHERE email IS NOT NULL;

CREATE TABLE refresh_tokens (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 token_hash CHAR(64) NOT NULL UNIQUE,
 expires_at TIMESTAMPTZ NOT NULL,
 revoked_at TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_refresh_user ON refresh_tokens(user_id);

CREATE TABLE categories (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 name VARCHAR(100) NOT NULL UNIQUE,
 slug VARCHAR(120) NOT NULL UNIQUE,
 sort_order INT NOT NULL DEFAULT 0,
 active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE products (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 category_id UUID NOT NULL REFERENCES categories(id),
 name VARCHAR(180) NOT NULL,
 slug VARCHAR(200) NOT NULL UNIQUE,
 description TEXT,
 base_price NUMERIC(12,2) NOT NULL CHECK(base_price >= 0),
 image_url TEXT,
 status product_status NOT NULL DEFAULT 'DRAFT',
 min_lead_hours INT NOT NULL DEFAULT 24 CHECK(min_lead_hours >= 0),
 production_units INT NOT NULL DEFAULT 1 CHECK(production_units > 0),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_products_category_status ON products(category_id,status);

CREATE TABLE customization_groups (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 name VARCHAR(100) NOT NULL,
 code VARCHAR(80) NOT NULL UNIQUE,
 selection_type VARCHAR(10) NOT NULL CHECK(selection_type IN ('SINGLE','MULTIPLE','TEXT')),
 min_selections INT NOT NULL DEFAULT 0 CHECK(min_selections >= 0),
 max_selections INT NOT NULL DEFAULT 1 CHECK(max_selections >= min_selections),
 CHECK(selection_type <> 'TEXT' OR (min_selections <= 1 AND max_selections = 1))
);
CREATE TABLE customization_options (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 group_id UUID NOT NULL REFERENCES customization_groups(id),
 name VARCHAR(120) NOT NULL,
 price_delta NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK(price_delta >= 0),
 active BOOLEAN NOT NULL DEFAULT TRUE,
 UNIQUE(id,group_id),
 UNIQUE(group_id,name)
);
CREATE TABLE product_customization_groups (
 product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
 group_id UUID NOT NULL REFERENCES customization_groups(id),
 required BOOLEAN NOT NULL DEFAULT FALSE,
 PRIMARY KEY(product_id,group_id)
);
CREATE TABLE product_customization_options (
 product_id UUID NOT NULL,
 group_id UUID NOT NULL,
 option_id UUID NOT NULL,
 PRIMARY KEY(product_id,option_id),
 FOREIGN KEY(product_id,group_id) REFERENCES product_customization_groups(product_id,group_id) ON DELETE CASCADE,
 FOREIGN KEY(option_id,group_id) REFERENCES customization_options(id,group_id)
);

CREATE TABLE delivery_slots (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 start_at TIMESTAMPTZ NOT NULL,
 end_at TIMESTAMPTZ NOT NULL,
 capacity_units INT NOT NULL CHECK(capacity_units > 0),
 reserved_units INT NOT NULL DEFAULT 0 CHECK(reserved_units >= 0),
 active BOOLEAN NOT NULL DEFAULT TRUE,
 CHECK(end_at > start_at),
 CHECK(reserved_units <= capacity_units),
 UNIQUE(start_at,end_at)
);

CREATE TABLE orders (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 order_number VARCHAR(30) NOT NULL UNIQUE,
 user_id UUID NOT NULL REFERENCES users(id),
 delivery_slot_id UUID NOT NULL REFERENCES delivery_slots(id),
 status order_status NOT NULL DEFAULT 'PENDING_PAYMENT',
 customer_name VARCHAR(150) NOT NULL,
 customer_phone VARCHAR(20) NOT NULL,
 delivery_address TEXT NOT NULL,
 delivery_area VARCHAR(120) NOT NULL,
 delivery_notes TEXT,
 subtotal NUMERIC(12,2) NOT NULL CHECK(subtotal >= 0),
 delivery_fee NUMERIC(12,2) NOT NULL CHECK(delivery_fee >= 0),
 discount_amount NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK(discount_amount >= 0),
 total_amount NUMERIC(12,2) NOT NULL CHECK(total_amount >= 0),
 currency CHAR(3) NOT NULL DEFAULT 'BDT' CHECK(currency = 'BDT'),
 payment_method payment_method NOT NULL,
 idempotency_key UUID NOT NULL,
 expires_at TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(user_id,idempotency_key),
 CHECK(total_amount = subtotal + delivery_fee - discount_amount)
);
CREATE INDEX idx_orders_user_created ON orders(user_id,created_at DESC);
CREATE INDEX idx_orders_status_created ON orders(status,created_at DESC);
CREATE INDEX idx_orders_slot ON orders(delivery_slot_id);

CREATE TABLE order_items (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 order_id UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
 product_id UUID NOT NULL REFERENCES products(id),
 product_name VARCHAR(180) NOT NULL,
 quantity INT NOT NULL CHECK(quantity > 0),
 unit_base_price NUMERIC(12,2) NOT NULL CHECK(unit_base_price >= 0),
 unit_options_price NUMERIC(12,2) NOT NULL DEFAULT 0,
 unit_price NUMERIC(12,2) NOT NULL CHECK(unit_price >= 0),
 line_total NUMERIC(12,2) NOT NULL CHECK(line_total >= 0),
 production_units INT NOT NULL CHECK(production_units > 0),
 CHECK(unit_price = unit_base_price + unit_options_price),
 CHECK(line_total = unit_price * quantity)
);
CREATE INDEX idx_items_order ON order_items(order_id);

CREATE TABLE order_item_customizations (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 order_item_id UUID NOT NULL REFERENCES order_items(id) ON DELETE CASCADE,
 group_id UUID NOT NULL REFERENCES customization_groups(id),
 option_id UUID REFERENCES customization_options(id),
 group_name VARCHAR(100) NOT NULL,
 option_name VARCHAR(120),
 text_value VARCHAR(250),
 price_delta NUMERIC(12,2) NOT NULL DEFAULT 0,
 CHECK((option_id IS NOT NULL AND text_value IS NULL) OR (option_id IS NULL AND text_value IS NOT NULL))
);
CREATE INDEX idx_item_customizations ON order_item_customizations(order_item_id);

CREATE TABLE slot_reservations (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 order_id UUID NOT NULL UNIQUE REFERENCES orders(id),
 slot_id UUID NOT NULL REFERENCES delivery_slots(id),
 reserved_units INT NOT NULL CHECK(reserved_units > 0),
 status reservation_status NOT NULL DEFAULT 'HELD',
 expires_at TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_slot_holds_expiry ON slot_reservations(expires_at) WHERE status = 'HELD';

CREATE TABLE ingredients (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 name VARCHAR(120) NOT NULL UNIQUE,
 unit VARCHAR(20) NOT NULL,
 on_hand NUMERIC(14,3) NOT NULL DEFAULT 0 CHECK(on_hand >= 0),
 reserved NUMERIC(14,3) NOT NULL DEFAULT 0 CHECK(reserved >= 0),
 CHECK(reserved <= on_hand)
);
CREATE TABLE product_ingredients (
 product_id UUID NOT NULL REFERENCES products(id),
 ingredient_id UUID NOT NULL REFERENCES ingredients(id),
 quantity_per_unit NUMERIC(14,3) NOT NULL CHECK(quantity_per_unit > 0),
 PRIMARY KEY(product_id,ingredient_id)
);
CREATE TABLE inventory_reservations (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 order_id UUID NOT NULL REFERENCES orders(id),
 ingredient_id UUID NOT NULL REFERENCES ingredients(id),
 quantity NUMERIC(14,3) NOT NULL CHECK(quantity > 0),
 status reservation_status NOT NULL DEFAULT 'HELD',
 UNIQUE(order_id,ingredient_id)
);

CREATE TABLE payments (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 order_id UUID NOT NULL REFERENCES orders(id),
 method payment_method NOT NULL,
 provider VARCHAR(60),
 provider_session_id VARCHAR(150),
 provider_transaction_id VARCHAR(150),
 amount NUMERIC(12,2) NOT NULL CHECK(amount >= 0),
 currency CHAR(3) NOT NULL DEFAULT 'BDT' CHECK(currency = 'BDT'),
 status payment_status NOT NULL DEFAULT 'PENDING',
 failure_reason TEXT,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_payments_order ON payments(order_id);
CREATE UNIQUE INDEX uq_payment_transaction ON payments(provider,provider_transaction_id) WHERE provider_transaction_id IS NOT NULL;
CREATE UNIQUE INDEX uq_payment_session ON payments(provider,provider_session_id) WHERE provider_session_id IS NOT NULL;
CREATE UNIQUE INDEX uq_payment_success ON payments(order_id) WHERE status = 'SUCCEEDED';

CREATE TABLE payment_webhook_events (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 provider VARCHAR(60) NOT NULL,
 event_id VARCHAR(180) NOT NULL,
 payment_id UUID REFERENCES payments(id),
 payload JSONB NOT NULL,
 received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 processed_at TIMESTAMPTZ,
 UNIQUE(provider,event_id)
);
CREATE TABLE outbox_events (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 aggregate_type VARCHAR(80) NOT NULL,
 aggregate_id UUID NOT NULL,
 event_type VARCHAR(120) NOT NULL,
 payload JSONB NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 processed_at TIMESTAMPTZ
);
CREATE INDEX idx_outbox_pending ON outbox_events(created_at) WHERE processed_at IS NULL;
