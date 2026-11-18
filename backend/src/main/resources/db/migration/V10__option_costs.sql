-- Custom cakes: what an option costs the kitchen on top of its product. Extra production units
-- add to the product's per item; an option's lead time applies when it is stricter than the
-- product's; option ingredients add to the product recipe per item.
ALTER TABLE customization_options
  ADD COLUMN extra_production_units INT NOT NULL DEFAULT 0
    CHECK (extra_production_units BETWEEN 0 AND 100),
  ADD COLUMN min_lead_hours INT CHECK (min_lead_hours BETWEEN 0 AND 720);
CREATE TABLE option_ingredients (
 option_id UUID NOT NULL REFERENCES customization_options(id),
 ingredient_id UUID NOT NULL REFERENCES ingredients(id),
 quantity_per_unit NUMERIC(14,3) NOT NULL CHECK (quantity_per_unit > 0),
 PRIMARY KEY (option_id, ingredient_id)
);
CREATE INDEX idx_option_ingredients_ingredient ON option_ingredients(ingredient_id);
