package com.bakedandbrowned.ordering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.bakedandbrowned.common.web.ApiException;
import com.bakedandbrowned.ordering.service.HoldExpiryJob;
import com.bakedandbrowned.ordering.service.OrderCancellationService;

/**
 * Payment hold expiry (ADR-0008, business rules acceptance 11): overdue unpaid orders expire once
 * and release their holds once, also when runs overlap or race a cancellation; MANUAL orders under
 * review wait for the review window.
 */
class HoldExpiryIntegrationTests extends CheckoutIntegrationTest {

	@Autowired
	private HoldExpiryJob job;

	@Autowired
	private OrderCancellationService cancellation;

	@Test
	void overdueUnpaidOrdersExpireAndReleaseTheirHolds() throws Exception {
		String customer = bearer("CUSTOMER");
		UUID online = overdue(placeOrder(customer, "ONLINE", slot, line(fudge, 1, six)));
		UUID manual = overdue(placeOrder(customer, "MANUAL", slot, line(fudge, 1, six)));
		UUID stillHeld = placeOrder(customer, "ONLINE", slot, line(cookies, 1));
		UUID cod = placeOrder(customer, "COD", slot, line(cookies, 1));
		assertThat(reservedUnits(slot)).isEqualTo(6);

		assertThat(job.expireDue()).isEqualTo(2);

		for (UUID expired : List.of(online, manual)) {
			assertThat(orderStatus(expired)).isEqualTo("EXPIRED");
			assertThat(paymentStatus(expired)).isEqualTo("CANCELLED");
			assertThat(slotReservationStatus(expired)).isEqualTo("RELEASED");
			assertThat(stockReservationStatuses(expired)).containsExactly("RELEASED");
			assertThat(events(expired)).containsExactly("order.placed", "order.expired");
			assertThat(jdbc.queryForMap("""
					SELECT from_status::text AS from_status, actor_type::text AS actor, actor_user_id, reason
					FROM order_status_history WHERE order_id = ? AND to_status = 'EXPIRED'""", expired))
				.containsEntry("from_status", "PENDING_PAYMENT")
				.containsEntry("actor", "SYSTEM")
				.containsEntry("actor_user_id", null)
				.containsEntry("reason", "Payment was not completed within the hold period.");
		}
		assertThat(orderStatus(stillHeld)).isEqualTo("PENDING_PAYMENT");
		assertThat(slotReservationStatus(stillHeld)).isEqualTo("HELD");
		assertThat(orderStatus(cod)).isEqualTo("CONFIRMED");
		assertThat(reservedUnits(slot)).isEqualTo(2);
		assertThat(reservedStock(chocolate)).isEqualByComparingTo("0");

		// The customer now sees an expired order that can no longer be cancelled.
		cancel(customer, online, null).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(OrderCancellationService.INVALID_STATE));
	}

	@Test
	void aSecondRunFindsNothingToDo() throws Exception {
		String customer = bearer("CUSTOMER");
		placeOrder(customer, "COD", slot, line(cookies, 1));
		overdue(placeOrder(customer, "ONLINE", slot, line(fudge, 1, six)));

		assertThat(job.expireDue()).isEqualTo(1);
		assertThat(job.expireDue()).isZero();

		assertThat(reservedUnits(slot)).isEqualTo(1);
		assertThat(reservedStock(chocolate)).isEqualByComparingTo("0");
		assertThat(count("outbox_events")).isEqualTo(3); // 2 placed + 1 expired
	}

	@Test
	void concurrentRunsExpireEachOrderExactlyOnce() throws Exception {
		String customer = bearer("CUSTOMER");
		placeOrder(customer, "COD", slot, line(cookies, 1));
		List<UUID> orders = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			orders.add(overdue(placeOrder(customer, "ONLINE", slot, line(fudge, 1, six))));
		}
		assertThat(reservedUnits(slot)).isEqualTo(9);

		List<Integer> counts = race(6, i -> () -> job.expireDue());

		assertThat(counts.stream().mapToInt(Integer::intValue).sum()).isEqualTo(4);
		assertThat(reservedUnits(slot)).isEqualTo(1);
		assertThat(reservedStock(chocolate)).isEqualByComparingTo("0");
		for (UUID orderId : orders) {
			assertThat(orderStatus(orderId)).isEqualTo("EXPIRED");
			assertThat(jdbc.queryForObject(
					"SELECT count(*) FROM order_status_history WHERE order_id = ? AND to_status = 'EXPIRED'",
					Integer.class, orderId)).isEqualTo(1);
			assertThat(events(orderId)).containsExactly("order.placed", "order.expired");
		}
	}

	@RepeatedTest(5)
	void expiryRacingACancellationReleasesOnce() throws Exception {
		String customerBearer = bearer("CUSTOMER");
		UUID customer = userIdOf(customerBearer);
		placeOrder(customerBearer, "COD", slot, line(cookies, 1));
		UUID orderId = overdue(placeOrder(customerBearer, "ONLINE", slot, line(fudge, 1, six)));

		List<String> outcomes = race(2, i -> () -> {
			if (i == 0) {
				return job.expireDue() == 1 ? "EXPIRED" : "SKIPPED";
			}
			try {
				cancellation.cancelByCustomer(orderId, customer, null);
				return "CANCELLED";
			}
			catch (ApiException ex) {
				return ex.code();
			}
		});

		// Exactly one of them closed the order; the other found it locked or already closed.
		String status = orderStatus(orderId);
		assertThat(status).isIn("EXPIRED", "CANCELLED");
		assertThat(outcomes).contains(status);
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM order_status_history
				WHERE order_id = ? AND to_status IN ('EXPIRED', 'CANCELLED')""", Integer.class, orderId)).isEqualTo(1);
		assertThat(reservedUnits(slot)).isEqualTo(1);
		assertThat(reservedStock(chocolate)).isEqualByComparingTo("0");
		// A skipped expiry leaves nothing behind for the next run.
		assertThat(job.expireDue()).isZero();
	}

	@Test
	void aSubmittedManualPaymentPausesExpiryDuringTheReviewWindow() throws Exception {
		String customer = bearer("CUSTOMER");
		UUID orderId = overdue(placeOrder(customer, "MANUAL", slot, line(fudge, 1, six)));
		UUID submission = submitManualPayment(orderId, userIdOf(customer), Instant.now().minus(Duration.ofMinutes(20)));

		assertThat(job.expireDue()).isZero();
		assertThat(orderStatus(orderId)).isEqualTo("PENDING_PAYMENT");
		assertThat(slotReservationStatus(orderId)).isEqualTo("HELD");

		// Six hours after the submission the review window is over.
		jdbc.update("UPDATE manual_payment_submissions SET created_at = now() - interval '6 hours 1 minute' WHERE id = ?",
				submission);
		jdbc.update("UPDATE orders SET expires_at = now() - interval '6 hours' WHERE id = ?", orderId);
		assertThat(job.expireDue()).isEqualTo(1);
		assertThat(orderStatus(orderId)).isEqualTo("EXPIRED");
		assertThat(reservedUnits(slot)).isZero();
		// The submission stays as it is; a later verification is handled as a late payment.
		assertThat(jdbc.queryForObject("SELECT status::text FROM manual_payment_submissions WHERE id = ?",
				String.class, submission)).isEqualTo("SUBMITTED");
	}

	@Test
	void theManualReviewEndsTwoHoursBeforeTheSlot() throws Exception {
		String customer = bearer("CUSTOMER");
		UUID orderId = overdue(placeOrder(customer, "MANUAL", slot, line(fudge, 1, six)));
		submitManualPayment(orderId, userIdOf(customer), Instant.now().minus(Duration.ofMinutes(20)));
		// The slot is now less than 2 hours away (submitted 20 minutes ago, so the 6 h window is open).
		jdbc.update("UPDATE delivery_slots SET start_at = now() + interval '1 hour 59 minutes', "
				+ "end_at = now() + interval '4 hours' WHERE id = ?", slot);

		assertThat(job.expireDue()).isEqualTo(1);
		assertThat(orderStatus(orderId)).isEqualTo("EXPIRED");
	}

	@Test
	void aSubmissionAfterTheHoldDoesNotPauseExpiry() throws Exception {
		String customer = bearer("CUSTOMER");
		UUID orderId = overdue(placeOrder(customer, "MANUAL", slot, line(fudge, 1, six)));
		submitManualPayment(orderId, userIdOf(customer), Instant.now());

		assertThat(job.expireDue()).isEqualTo(1);
		assertThat(orderStatus(orderId)).isEqualTo("EXPIRED");
	}

	// ------------------------------------------------------------------ helpers

	/** Moves the order's 15-minute hold into the past. */
	private UUID overdue(UUID orderId) {
		jdbc.update("UPDATE orders SET expires_at = now() - interval '1 minute' WHERE id = ?", orderId);
		jdbc.update("UPDATE slot_reservations SET expires_at = now() - interval '1 minute' WHERE order_id = ?", orderId);
		return orderId;
	}

	private UUID submitManualPayment(UUID orderId, UUID customer, Instant at) {
		Map<String, Object> payment = jdbc.queryForMap("SELECT id FROM payments WHERE order_id = ?", orderId);
		return jdbc.queryForObject("""
				INSERT INTO manual_payment_submissions (payment_id, submitted_by, provider, transaction_reference, created_at)
				VALUES (?, ?, 'BKASH', ?, ?) RETURNING id""", UUID.class, payment.get("id"), customer,
				"TRX" + UUID.randomUUID().toString().substring(0, 8), java.sql.Timestamp.from(at));
	}

}
