package com.bakedandbrowned.delivery;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import com.bakedandbrowned.catalog.CatalogIntegrationTest;
import com.bakedandbrowned.common.time.BusinessTime;

/**
 * Delivery test base: slots are truncated and the seeded zones restored to their reference state
 * (inactive, no fee) before and after each test, so other test classes see only reference data.
 */
public abstract class DeliveryIntegrationTest extends CatalogIntegrationTest {

	static final String SEEDED = "('DHANMONDI','MOHAMMADPUR','MIRPUR','UTTARA','GULSHAN','BANANI')";

	@BeforeEach
	@AfterEach
	protected void resetDelivery() {
		jdbc.execute("TRUNCATE delivery_slots CASCADE");
		jdbc.update("DELETE FROM delivery_zones WHERE code NOT IN " + SEEDED);
		// Restore the V3 seed exactly (tests may rename zones or change fees to prove snapshots hold).
		jdbc.update("""
				UPDATE delivery_zones z SET name = s.name, sort_order = s.sort_order,
				       delivery_fee = NULL, min_order_amount = NULL, active = FALSE
				FROM (VALUES ('DHANMONDI', 'Dhanmondi', 10), ('MOHAMMADPUR', 'Mohammadpur', 20), ('MIRPUR', 'Mirpur', 30),
				             ('UTTARA', 'Uttara', 40), ('GULSHAN', 'Gulshan', 50), ('BANANI', 'Banani', 60))
				     AS s(code, name, sort_order)
				WHERE z.code = s.code""");
	}

	/** A Dhaka calendar date {@code days} from today. */
	protected static LocalDate dhakaDate(int days) {
		return LocalDate.now(BusinessTime.ZONE).plusDays(days);
	}

	/** An instant at {@code time} Dhaka on {@code date}. */
	protected static Instant at(LocalDate date, String time) {
		return date.atTime(LocalTime.parse(time)).atZone(BusinessTime.ZONE).toInstant();
	}

	protected UUID slot(Instant start, Duration length, int capacity, int reserved, boolean active) {
		return jdbc.queryForObject("""
				INSERT INTO delivery_slots (start_at, end_at, capacity_units, reserved_units, active)
				VALUES (?, ?, ?, ?, ?) RETURNING id""", UUID.class, java.sql.Timestamp.from(start),
				java.sql.Timestamp.from(start.plus(length)), capacity, reserved, active);
	}

	protected void configureZone(String code, String fee, String minimum, boolean active) {
		jdbc.update("""
				UPDATE delivery_zones SET delivery_fee = ?::numeric, min_order_amount = ?::numeric, active = ?
				WHERE code = ?""", fee, minimum, active, code);
	}

}
