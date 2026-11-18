package com.bakedandbrowned.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.bakedandbrowned.IntegrationTest;

/**
 * Base for tests that exercise database-level invariants directly with SQL (no entities yet).
 * Every test starts from the migrated schema with only reference data (seeded zones) present.
 */
@IntegrationTest
abstract class AbstractSchemaTest {

	/** PostgreSQL SQLSTATE codes used in assertions. */
	static final String UNIQUE_VIOLATION = "23505";
	static final String CHECK_VIOLATION = "23514";
	static final String NOT_NULL_VIOLATION = "23502";
	static final String RAISE_EXCEPTION = "P0001";

	private static final AtomicInteger SEQUENCE = new AtomicInteger();

	@Autowired
	protected JdbcTemplate jdbc;

	/**
	 * Runs before and after each test: before, so a test never sees another test's rows; after,
	 * so other test classes sharing the container only ever see reference data.
	 */
	@BeforeEach
	@AfterEach
	void resetToReferenceData() {
		// TRUNCATE skips row-level triggers, so append-only tables can be cleared between tests.
		jdbc.execute("""
				TRUNCATE users, categories, customization_groups, delivery_slots, ingredients,
				         outbox_events, audit_events, payment_webhook_events CASCADE""");
		jdbc.update("""
				DELETE FROM delivery_zones
				WHERE code NOT IN ('DHANMONDI','MOHAMMADPUR','MIRPUR','UTTARA','GULSHAN','BANANI')""");
	}

	// ------------------------------------------------------------------ assertions

	/** Runs the statement, expects PostgreSQL to reject it, and returns the server error. */
	protected static PSQLException rejected(Runnable statement) {
		Throwable thrown = catchThrowable(statement::run);
		assertThat(thrown).as("statement should have been rejected by the database").isNotNull();
		for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
			if (cause instanceof PSQLException psql) {
				return psql;
			}
		}
		throw new AssertionError("expected a PostgreSQL error", thrown);
	}

	protected static void assertRejectedBy(String constraint, Runnable statement) {
		PSQLException error = rejected(statement);
		assertThat(error.getServerErrorMessage()).isNotNull();
		assertThat(error.getServerErrorMessage().getConstraint()).as(error.getMessage()).isEqualTo(constraint);
	}

	protected static void assertRejectedWith(String sqlState, String table, Runnable statement) {
		PSQLException error = rejected(statement);
		assertThat(error.getSQLState()).as(error.getMessage()).isEqualTo(sqlState);
		assertThat(error.getServerErrorMessage()).isNotNull();
		assertThat(error.getServerErrorMessage().getTable()).isEqualTo(table);
	}

	protected static void assertAppendOnly(String table, Runnable statement) {
		PSQLException error = rejected(statement);
		assertThat(error.getSQLState()).isEqualTo(RAISE_EXCEPTION);
		assertThat(error.getMessage()).contains(table + " is append-only");
	}

	// ------------------------------------------------------------------ fixtures

	protected static int next() {
		return SEQUENCE.incrementAndGet();
	}

	/** Unique valid Bangladeshi mobile number (+8801[3-9]XXXXXXXX). */
	protected static String phone() {
		return "+88017%08d".formatted(next());
	}

	protected UUID user() {
		return jdbc.queryForObject("""
				INSERT INTO users (full_name, phone, password_hash) VALUES ('Test User', ?, 'hash')
				RETURNING id""", UUID.class, phone());
	}

	protected UUID admin() {
		return jdbc.queryForObject("""
				INSERT INTO users (full_name, phone, password_hash, role) VALUES ('Test Admin', ?, 'hash', 'ADMIN')
				RETURNING id""", UUID.class, phone());
	}

	protected UUID activeZone() {
		int n = next();
		return jdbc.queryForObject("""
				INSERT INTO delivery_zones (code, name, delivery_fee, active) VALUES (?, ?, 100.00, TRUE)
				RETURNING id""", UUID.class, "TEST_ZONE_" + n, "Test Zone " + n);
	}

	protected UUID slot(int capacityUnits) {
		return jdbc.queryForObject("""
				INSERT INTO delivery_slots (start_at, end_at, capacity_units)
				VALUES (now() + make_interval(days => 2, mins => ?), now() + make_interval(days => 2, mins => ?, hours => 2), ?)
				RETURNING id""", UUID.class, next(), next(), capacityUnits);
	}

	protected UUID order(UUID userId, String paymentMethod, String status) {
		return order(userId, UUID.randomUUID(), paymentMethod, status);
	}

	protected UUID order(UUID userId, UUID idempotencyKey, String paymentMethod, String status) {
		return jdbc.queryForObject("""
				INSERT INTO orders (order_number, user_id, delivery_slot_id, status, customer_name, customer_phone,
				                    delivery_address, delivery_area, delivery_zone_id, subtotal, delivery_fee,
				                    discount_amount, total_amount, payment_method, idempotency_key,
				                    cancellation_deadline, request_hash)
				VALUES ('BB-TEST-' || nextval('order_number_seq'), ?, ?, ?::order_status, 'Test Customer', ?,
				        'House 1, Road 2', 'Test Zone', ?, 1000.00, 100.00, 0.00, 1100.00, ?::payment_method, ?,
				        now() + interval '1 day', repeat('a', 64))
				RETURNING id""", UUID.class, userId, slot(10), status, phone(), activeZone(), paymentMethod,
				idempotencyKey);
	}

	protected UUID payment(UUID orderId, String method, String status) {
		return jdbc.queryForObject("""
				INSERT INTO payments (order_id, method, amount, status)
				VALUES (?, ?::payment_method, 1100.00, ?::payment_status)
				RETURNING id""", UUID.class, orderId, method, status);
	}

	protected UUID ingredient(BigDecimal onHand) {
		return jdbc.queryForObject("""
				INSERT INTO ingredients (name, unit, on_hand) VALUES (?, 'g', ?)
				RETURNING id""", UUID.class, "Ingredient " + next(), onHand);
	}

}
