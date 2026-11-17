package com.bakedandbrowned.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Delivery zones (ADR-0010) and slot capacity. */
class DeliverySchemaTests extends AbstractSchemaTest {

	@Test
	void seededZoneCannotBeActivatedWithoutFee() {
		assertRejectedBy("ck_zone_active_requires_fee",
				() -> jdbc.update("UPDATE delivery_zones SET active = TRUE WHERE code = 'DHANMONDI'"));
	}

	@Test
	void zoneCanBeActivatedOnceFeeIsConfigured() {
		jdbc.update("""
				INSERT INTO delivery_zones (code, name, delivery_fee, min_order_amount, active)
				VALUES ('TEST_ACTIVATION', 'Activation Test', 120.00, 500.00, FALSE)""");
		int updated = jdbc.update("UPDATE delivery_zones SET active = TRUE WHERE code = 'TEST_ACTIVATION'");
		assertThat(updated).isEqualTo(1);
	}

	@Test
	void zoneCodeMustBeUpperSnakeCase() {
		assertRejectedBy("delivery_zones_code_check", () -> jdbc.update(
				"INSERT INTO delivery_zones (code, name) VALUES ('dhaka-north', 'Dhaka North')"));
	}

	@Test
	void zoneFeesCannotBeNegative() {
		assertRejectedBy("delivery_zones_delivery_fee_check", () -> jdbc.update(
				"INSERT INTO delivery_zones (code, name, delivery_fee) VALUES ('NEG_FEE', 'Negative', -1.00)"));
	}

	@Test
	void guardedReservationNeverExceedsCapacity() {
		UUID slotId = slot(3);
		String reserve = """
				UPDATE delivery_slots SET reserved_units = reserved_units + ?
				WHERE id = ? AND active = TRUE AND reserved_units + ? <= capacity_units""";

		assertThat(jdbc.update(reserve, 2, slotId, 2)).isEqualTo(1);
		assertThat(jdbc.update(reserve, 2, slotId, 2)).as("would exceed capacity").isZero();
		assertThat(jdbc.update(reserve, 1, slotId, 1)).as("last unit").isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT reserved_units FROM delivery_slots WHERE id = ?", Integer.class, slotId))
			.isEqualTo(3);
	}

	@Test
	void capacityCannotBeReducedBelowReservations() {
		UUID slotId = slot(5);
		jdbc.update("UPDATE delivery_slots SET reserved_units = 4 WHERE id = ?", slotId);
		assertRejectedWith(CHECK_VIOLATION, "delivery_slots",
				() -> jdbc.update("UPDATE delivery_slots SET capacity_units = 3 WHERE id = ?", slotId));
	}

	@Test
	void closedSlotAcceptsNoNewReservations() {
		UUID slotId = slot(5);
		jdbc.update("UPDATE delivery_slots SET active = FALSE WHERE id = ?", slotId);
		int reserved = jdbc.update("""
				UPDATE delivery_slots SET reserved_units = reserved_units + 1
				WHERE id = ? AND active = TRUE AND reserved_units + 1 <= capacity_units""", slotId);
		assertThat(reserved).isZero();
	}

}
