-- Custom cakes: per product, pairs of options that cannot be chosen together. A pair is stored once
-- (option_a sorts before option_b); the service keeps both options allowed for the product, and
-- drops a pair when its product's customizations no longer allow one of them.
CREATE TABLE product_disallowed_option_pairs (
 product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
 option_a UUID NOT NULL REFERENCES customization_options(id),
 option_b UUID NOT NULL REFERENCES customization_options(id),
 PRIMARY KEY (product_id, option_a, option_b),
 CHECK (option_a < option_b)
);
CREATE INDEX idx_disallowed_pairs_option_b ON product_disallowed_option_pairs(option_b);
