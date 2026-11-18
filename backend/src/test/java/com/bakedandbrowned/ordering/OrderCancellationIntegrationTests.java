package com.bakedandbrowned.ordering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.bakedandbrowned.common.web.ApiException;
import com.bakedandbrowned.ordering.service.OrderCancellationService;

/**
 * {@code POST /orders/{id}/cancel} (ADR-0009): who may cancel and until when, one-time release of
 * slot and stock holds, payment handling and refund requests.
 */
class OrderCancellationIntegrationTests extends CheckoutIntegrationTest {

	@Autowired
	private OrderCancellationService cancellation;

	@Test
	void cancellingAConfirmedCodOrderReleasesItsHoldsOnce() throws Exception {
		String customer = bearer("CUSTOMER");
		UUID kept = placeOrder(customer, "COD", slot, line(cookies, 1)); // 1 unit, no stock
		UUID orderId = placeOrder(customer, "COD", slot, line(fudge, 1, six)); // 2 units, 100 g
		assertThat(reservedUnits(slot)).isEqualTo(3);
		assertThat(reservedStock(chocolate)).isEqualByComparingTo("100");

		cancel(customer, orderId, "{\"reason\":\"  Plans changed  \"}").andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CANCELLED"))
			.andExpect(jsonPath("$.paymentStatus").value("CANCELLED"))
			.andExpect(jsonPath("$.payment.refundStatus").isEmpty())
			.andExpect(jsonPath("$.cancellable").value(false))
			.andExpect(jsonPath("$.statusHistory[*].status", contains("CONFIRMED", "CANCELLED")));

		assertThat(reservedUnits(slot)).isEqualTo(1);
		assertThat(reservedStock(chocolate)).isEqualByComparingTo("0");
		assertThat(slotReservationStatus(orderId)).isEqualTo("RELEASED");
		assertThat(stockReservationStatuses(orderId)).containsExactly("RELEASED");
		assertThat(jdbc.queryForObject("SELECT released_at IS NOT NULL FROM slot_reservations WHERE order_id = ?",
				Boolean.class, orderId)).isTrue();
		assertThat(jdbc.queryForMap("""
				SELECT from_status::text AS from_status, actor_type::text AS actor, actor_user_id, reason
				FROM order_status_history WHERE order_id = ? AND to_status = 'CANCELLED'""", orderId))
			.containsEntry("from_status", "CONFIRMED")
			.containsEntry("actor", "CUSTOMER")
			.containsEntry("actor_user_id", userIdOf(customer))
			.containsEntry("reason", "Plans changed");
		assertThat(events(orderId)).containsExactly("order.placed", "order.cancelled");
		assertThat(count("refunds")).isZero();

		// Repeating the request changes nothing: the holds were released exactly once.
		cancel(customer, orderId, null).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(OrderCancellationService.INVALID_STATE));
		assertThat(reservedUnits(slot)).isEqualTo(1);
		assertThat(reservedStock(chocolate)).isEqualByComparingTo("0");
		assertThat(events(orderId)).containsExactly("order.placed", "order.cancelled");
		assertThat(orderStatus(kept)).isEqualTo("CONFIRMED");
	}

	@Test
	void anUnpaidOnlineOrderCanBeCancelledWithoutABody() throws Exception {
		String customer = bearer("CUSTOMER");
		UUID orderId = placeOrder(customer, "ONLINE", slot, line(fudge, 1, six));

		cancel(customer, orderId, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CANCELLED"))
			.andExpect(jsonPath("$.statusHistory[*].status", contains("PENDING_PAYMENT", "CANCELLED")));

		assertThat(paymentStatus(orderId)).isEqualTo("CANCELLED");
		assertThat(slotReservationStatus(orderId)).isEqualTo("RELEASED");
		assertThat(stockReservationStatuses(orderId)).containsExactly("RELEASED");
		assertThat(reservedUnits(slot)).isZero();
		assertThat(reservedStock(chocolate)).isEqualByComparingTo("0");
		assertThat(jdbc.queryForObject(
				"SELECT reason FROM order_status_history WHERE order_id = ? AND to_status = 'CANCELLED'", String.class,
				orderId)).isNull();
	}

	@Test
	void cancellationClosesAtTheCutoffBeforeTheSlot() throws Exception {
		// Business rules acceptance 12, with a 24 h cutoff and no lead time so near slots can be booked.
		jdbc.update("UPDATE products SET min_lead_hours = 0");
		String customer = bearer("CUSTOMER");
		Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		UUID in25h = slot(now.plus(Duration.ofHours(25)), Duration.ofHours(2), 10, 0, true);
		UUID in23h = slot(now.plus(Duration.ofHours(23)), Duration.ofHours(2), 10, 0, true);
		UUID early = placeOrder(customer, "COD", in25h, line(fudge, 1, six));
		UUID late = placeOrder(customer, "COD", in23h, line(fudge, 1, six));

		cancel(customer, early, null).andExpect(status().isOk());
		cancel(customer, late, null).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(OrderCancellationService.CANCELLATION_WINDOW_CLOSED));

		assertThat(orderStatus(late)).isEqualTo("CONFIRMED");
		assertThat(reservedUnits(in23h)).isEqualTo(2);
		assertThat(slotReservationStatus(late)).isEqualTo("CONFIRMED");
		assertThat(reservedStock(chocolate)).isEqualByComparingTo("100");
		assertThat(events(late)).containsExactly("order.placed");
	}

	@Test
	void aMixedOrderUsesTheStrictestCutoff() throws Exception {
		jdbc.update("UPDATE products SET min_lead_hours = 0");
		jdbc.update("UPDATE products SET cancellation_cutoff_hours = 48 WHERE id = ?", cookies);
		String customer = bearer("CUSTOMER");
		UUID in47h = slot(Instant.now().plus(Duration.ofHours(47)).truncatedTo(ChronoUnit.SECONDS),
				Duration.ofHours(2), 10, 0, true);
		UUID brownieOnly = placeOrder(customer, "COD", in47h, line(fudge, 1, six));
		UUID mixed = placeOrder(customer, "COD", in47h, line(fudge, 1, six), line(cookies, 1));

		cancel(customer, mixed, null).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(OrderCancellationService.CANCELLATION_WINDOW_CLOSED));
		cancel(customer, brownieOnly, null).andExpect(status().isOk());
	}

	@Test
	void theDeadlineSnapshotIsUsedNotTheCurrentProductCutoff() throws Exception {
		String customer = bearer("CUSTOMER");
		UUID orderId = placeOrder(customer, "COD", slot, line(fudge, 1, six));
		// An ADMIN later tightens the cutoff to 72 h: the order keeps its 24 h deadline.
		jdbc.update("UPDATE products SET cancellation_cutoff_hours = 72 WHERE id = ?", fudge);

		cancel(customer, orderId, null).andExpect(status().isOk());
	}

	@Test
	void onlyTheOwnerMayCancel() throws Exception {
		String owner = bearer("CUSTOMER");
		UUID orderId = placeOrder(owner, "COD", slot, line(fudge, 1, six));

		// Someone else's order looks exactly like a missing one, also to an ADMIN on this endpoint.
		cancel(bearer("CUSTOMER"), orderId, null).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
		cancel(bearer("ADMIN"), orderId, null).andExpect(status().isNotFound());
		cancel(owner, UUID.randomUUID(), null).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
		mockMvc.perform(post("/api/v1/orders/{id}/cancel", orderId)).andExpect(status().isUnauthorized());

		assertThat(orderStatus(orderId)).isEqualTo("CONFIRMED");
		assertThat(reservedUnits(slot)).isEqualTo(2);
	}

	@Test
	void aReasonLongerThan500CharactersFailsValidation() throws Exception {
		String customer = bearer("CUSTOMER");
		UUID orderId = placeOrder(customer, "COD", slot, line(fudge, 1, six));

		cancel(customer, orderId, "{\"reason\":\"" + "x".repeat(501) + "\"}").andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		assertThat(orderStatus(orderId)).isEqualTo("CONFIRMED");
		cancel(customer, orderId, "{\"reason\":\"" + "x".repeat(500) + "\"}").andExpect(status().isOk());
	}

	@Test
	void cancellingAPaidOrderOpensARefundRequestInsteadOfRefunding() throws Exception {
		String customer = bearer("CUSTOMER");
		UUID orderId = placeOrder(customer, "ONLINE", slot, line(fudge, 1, six));
		// The gateway confirmed the payment (Phase 3 flow, simulated here).
		jdbc.update("UPDATE payments SET status = 'SUCCEEDED' WHERE order_id = ?", orderId);
		jdbc.update("UPDATE orders SET status = 'CONFIRMED', expires_at = NULL WHERE id = ?", orderId);

		cancel(customer, orderId, null).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CANCELLED"))
			.andExpect(jsonPath("$.paymentStatus").value("SUCCEEDED"))
			.andExpect(jsonPath("$.payment.refundStatus").value("REQUESTED"));

		Map<String, Object> refund = jdbc.queryForMap("""
				SELECT status::text AS status, amount, request_reason, requested_by_type::text AS by_type, requested_by
				FROM refunds WHERE order_id = ?""", orderId);
		assertThat(refund).containsEntry("status", "REQUESTED")
			.containsEntry("request_reason", "Order cancelled by the customer.")
			.containsEntry("by_type", "CUSTOMER")
			.containsEntry("requested_by", userIdOf(customer));
		assertThat((BigDecimal) refund.get("amount")).isEqualByComparingTo("1310.00");
		assertThat(paymentStatus(orderId)).isEqualTo("SUCCEEDED");
		assertThat(reservedUnits(slot)).isZero();
	}

	@Test
	void concurrentCancellationsOfOneOrderReleaseOnce() throws Exception {
		String customerBearer = bearer("CUSTOMER");
		UUID customer = userIdOf(customerBearer);
		placeOrder(customerBearer, "COD", slot, line(cookies, 1));
		UUID orderId = placeOrder(customerBearer, "COD", slot, line(fudge, 1, six));

		List<String> outcomes = race(6, i -> () -> {
			try {
				cancellation.cancelByCustomer(orderId, customer, null);
				return "OK";
			}
			catch (ApiException ex) {
				return ex.code();
			}
		});

		assertThat(outcomes).filteredOn("OK"::equals).hasSize(1);
		assertThat(outcomes).filteredOn(o -> !"OK".equals(o))
			.hasSize(5)
			.allSatisfy(o -> assertThat(o).isEqualTo(OrderCancellationService.INVALID_STATE));
		assertThat(reservedUnits(slot)).isEqualTo(1);
		assertThat(reservedStock(chocolate)).isEqualByComparingTo("0");
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM order_status_history WHERE order_id = ? AND to_status = 'CANCELLED'", Integer.class,
				orderId)).isEqualTo(1);
	}

}
