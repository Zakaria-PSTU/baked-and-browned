package com.bakedandbrowned.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Orders, idempotency and status history (ADR-0009, ADR-0014). */
class OrderingSchemaTests extends AbstractSchemaTest {

	@Test
	void idempotencyKeyIsUniquePerUser() {
		UUID customer = user();
		UUID key = UUID.randomUUID();
		order(customer, key, "COD", "CONFIRMED");

		assertRejectedBy("orders_user_id_idempotency_key_key", () -> order(customer, key, "COD", "CONFIRMED"));
		// The same key from another customer is a different checkout.
		order(user(), key, "COD", "CONFIRMED");
	}

	@Test
	void totalMustEqualSubtotalPlusFeeMinusDiscount() {
		UUID orderId = order(user(), "COD", "CONFIRMED");
		assertRejectedWith(CHECK_VIOLATION, "orders",
				() -> jdbc.update("UPDATE orders SET total_amount = 999.00 WHERE id = ?", orderId));
	}

	@Test
	void ordersRequireCancellationDeadlineAndRequestHash() {
		UUID orderId = order(user(), "ONLINE", "PENDING_PAYMENT");
		assertRejectedWith(NOT_NULL_VIOLATION, "orders",
				() -> jdbc.update("UPDATE orders SET cancellation_deadline = NULL WHERE id = ?", orderId));
		assertRejectedWith(NOT_NULL_VIOLATION, "orders",
				() -> jdbc.update("UPDATE orders SET request_hash = NULL WHERE id = ?", orderId));
	}

	@Test
	void orderNumberSequenceIncrements() {
		long first = jdbc.queryForObject("SELECT nextval('order_number_seq')", Long.class);
		long second = jdbc.queryForObject("SELECT nextval('order_number_seq')", Long.class);
		assertThat(second).isGreaterThan(first);
	}

	@Test
	void adminCancellationRequiresReason() {
		UUID adminId = admin();
		UUID orderId = order(user(), "COD", "CANCELLED");
		String insert = """
				INSERT INTO order_status_history (order_id, from_status, to_status, actor_type, actor_user_id, reason)
				VALUES (?, 'CONFIRMED', 'CANCELLED', ?::actor_type, ?, ?)""";

		assertRejectedBy("ck_admin_cancel_reason", () -> jdbc.update(insert, orderId, "ADMIN", adminId, null));
		jdbc.update(insert, orderId, "ADMIN", adminId, "customer unreachable");
	}

	@Test
	void customerCancellationNeedsNoReasonButMustIdentifyCustomer() {
		UUID customer = user();
		UUID orderId = order(customer, "COD", "CANCELLED");
		String insert = """
				INSERT INTO order_status_history (order_id, from_status, to_status, actor_type, actor_user_id)
				VALUES (?, 'CONFIRMED', 'CANCELLED', 'CUSTOMER', ?)""";

		assertRejectedWith(CHECK_VIOLATION, "order_status_history", () -> jdbc.update(insert, orderId, null));
		jdbc.update(insert, orderId, customer);
	}

	@Test
	void statusHistoryRejectsNoOpTransitionsAndEdits() {
		UUID orderId = order(user(), "COD", "CONFIRMED");
		assertRejectedWith(CHECK_VIOLATION, "order_status_history", () -> jdbc.update("""
				INSERT INTO order_status_history (order_id, from_status, to_status, actor_type)
				VALUES (?, 'CONFIRMED', 'CONFIRMED', 'SYSTEM')""", orderId));

		UUID entry = jdbc.queryForObject("""
				INSERT INTO order_status_history (order_id, from_status, to_status, actor_type)
				VALUES (?, NULL, 'CONFIRMED', 'SYSTEM') RETURNING id""", UUID.class, orderId);
		assertAppendOnly("order_status_history",
				() -> jdbc.update("UPDATE order_status_history SET reason = 'edited' WHERE id = ?", entry));
	}

}
