package com.bakedandbrowned.inventory;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import com.bakedandbrowned.delivery.DeliveryIntegrationTest;

/** Inventory test base: ingredients (and by cascade recipes, reservations and movements) reset per test. */
abstract class InventoryIntegrationTest extends DeliveryIntegrationTest {

	@BeforeEach
	@AfterEach
	protected void resetInventory() {
		jdbc.execute("TRUNCATE ingredients CASCADE");
	}

	protected UUID ingredient(String name, String unit, String onHand) {
		return jdbc.queryForObject("INSERT INTO ingredients (name, unit, on_hand) VALUES (?, ?, ?) RETURNING id",
				UUID.class, name, unit, new BigDecimal(onHand));
	}

	protected BigDecimal column(UUID ingredient, String column) {
		return jdbc.queryForObject("SELECT " + column + " FROM ingredients WHERE id = ?", BigDecimal.class, ingredient);
	}

	/** A minimal real order row (reservations reference orders). */
	protected UUID order() {
		UUID user = jdbc.queryForObject("""
				INSERT INTO users (full_name, phone, password_hash)
				VALUES ('Customer', '+88017' || lpad((floor(random() * 100000000))::text, 8, '0'), 'x') RETURNING id""",
				UUID.class);
		configureZone("DHANMONDI", "60.00", null, true);
		UUID zone = jdbc.queryForObject("SELECT id FROM delivery_zones WHERE code = 'DHANMONDI'", UUID.class);
		UUID slot = slot(Instant.now().plus(Duration.ofDays(2)).plusSeconds((long) (Math.random() * 1_000_000)),
				Duration.ofHours(2), 10, 0, true);
		return jdbc.queryForObject("""
				INSERT INTO orders (order_number, user_id, delivery_slot_id, status, customer_name, customer_phone,
				                    delivery_address, delivery_area, delivery_zone_id, subtotal, delivery_fee,
				                    discount_amount, total_amount, payment_method, idempotency_key,
				                    cancellation_deadline, request_hash)
				VALUES ('BB-TEST-' || nextval('order_number_seq'), ?, ?, 'CONFIRMED', 'Customer', '+8801712345678',
				        'House 1', 'Dhanmondi', ?, 1000.00, 60.00, 0.00, 1060.00, 'COD', gen_random_uuid(),
				        now() + interval '1 day', repeat('a', 64))
				RETURNING id""", UUID.class, user, slot, zone);
	}

}
