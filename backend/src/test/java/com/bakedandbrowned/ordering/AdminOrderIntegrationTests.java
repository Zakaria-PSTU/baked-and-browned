package com.bakedandbrowned.ordering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.bakedandbrowned.common.outbox.OutboxDispatcher;
import com.bakedandbrowned.common.web.ApiException;
import com.bakedandbrowned.notification.sms.FakeSmsSender;
import com.bakedandbrowned.notification.sms.SmsSender.SmsMessage;
import com.bakedandbrowned.ordering.dto.AdminOrderDtos.OrderStatus;
import com.bakedandbrowned.ordering.service.HoldExpiryJob;
import com.bakedandbrowned.ordering.service.OrderAdminService;
import com.bakedandbrowned.ordering.service.OrderCancellationService;

/**
 * ADMIN order operations (architecture, Order state machine): one fulfilment step at a time with
 * one-time ingredient consumption, cancellation with a reason, the forbidden transitions, the
 * admin views, audit, history and the dispatched notice.
 */
class AdminOrderIntegrationTests extends CheckoutIntegrationTest {

	@Autowired
	private OrderAdminService admins;

	@Autowired
	private OrderCancellationService cancellations;

	@Autowired
	private HoldExpiryJob expiry;

	@Autowired
	private OutboxDispatcher dispatcher;

	@Autowired
	private FakeSmsSender sms;

	private String admin;

	private String customer;

	@BeforeEach
	void people() {
		admin = bearer("ADMIN");
		customer = bearer("CUSTOMER");
		sms.reset();
	}

	// ------------------------------------------------------------------ fulfilment

	@Test
	void anOrderMovesThroughFulfilmentOneStepAtATime() throws Exception {
		UUID orderId = placeOrder(customer, "COD", slot, line(fudge, 1, six));

		change(orderId, "PREPARING", null).andExpect(status().isOk())
			.andExpect(jsonPath("$.orderId").value(orderId.toString()))
			.andExpect(jsonPath("$.status").value("PREPARING"));
		// Preparation consumes the reserved chocolate exactly once; the slot stays booked.
		assertThat(column(chocolate, "on_hand")).isEqualByComparingTo("900");
		assertThat(reservedStock(chocolate)).isEqualByComparingTo("0");
		assertThat(stockReservationStatuses(orderId)).containsExactly("CONSUMED");
		assertThat(jdbc.queryForMap("""
				SELECT quantity_delta, on_hand_after, actor_type::text AS actor, actor_user_id FROM inventory_movements
				WHERE order_id = ? AND movement_type = 'CONSUMPTION'""", orderId))
			.containsEntry("actor", "ADMIN")
			.containsEntry("actor_user_id", userIdOf(admin))
			.satisfies(m -> assertThat((BigDecimal) m.get("quantity_delta")).isEqualByComparingTo("-100"));
		assertThat(reservedUnits(slot)).isEqualTo(2);

		change(orderId, "READY", "Boxed and labelled").andExpect(status().isOk());
		change(orderId, "OUT_FOR_DELIVERY", null).andExpect(status().isOk());
		change(orderId, "DELIVERED", null).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("DELIVERED"));

		assertThat(jdbc.queryForList("""
				SELECT to_status::text FROM order_status_history WHERE order_id = ? AND actor_type = 'ADMIN'
				ORDER BY created_at, id""", String.class, orderId))
			.containsExactly("PREPARING", "READY", "OUT_FOR_DELIVERY", "DELIVERED");
		assertThat(jdbc.queryForObject(
				"SELECT reason FROM order_status_history WHERE order_id = ? AND to_status = 'READY'", String.class,
				orderId)).isEqualTo("Boxed and labelled");
		assertThat(jdbc.queryForList("""
				SELECT details->>'fromStatus' || '>' || (details->>'toStatus') FROM audit_events
				WHERE entity_id = ? AND action = 'order.status_changed' ORDER BY created_at, id""", String.class,
				orderId))
			.containsExactly("CONFIRMED>PREPARING", "PREPARING>READY", "READY>OUT_FOR_DELIVERY",
					"OUT_FOR_DELIVERY>DELIVERED");
		assertThat(events(orderId)).containsExactly("order.placed", "order.dispatched");
		assertThat(column(chocolate, "on_hand")).isEqualByComparingTo("900");

		// Delivered is final.
		change(orderId, "CANCELLED", "Too late").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(OrderAdminService.INVALID_TRANSITION));
	}

	@Test
	void stepsCannotBeSkippedOrReversed() throws Exception {
		UUID orderId = placeOrder(customer, "COD", slot, line(fudge, 1, six));

		for (String target : List.of("READY", "OUT_FOR_DELIVERY", "DELIVERED", "CONFIRMED", "PENDING_PAYMENT",
				"EXPIRED")) {
			change(orderId, target, null).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value(OrderAdminService.INVALID_TRANSITION));
		}
		change(orderId, "PREPARING", null).andExpect(status().isOk());
		change(orderId, "PREPARING", null).andExpect(status().isConflict());
		change(orderId, "CONFIRMED", null).andExpect(status().isConflict());

		assertThat(orderStatus(orderId)).isEqualTo("PREPARING");
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM inventory_movements WHERE order_id = ? AND movement_type = 'CONSUMPTION'",
				Integer.class, orderId)).isEqualTo(1);
	}

	@Test
	void unpaidAndExpiredOrdersAreNotMovedByHand() throws Exception {
		UUID unpaid = placeOrder(customer, "MANUAL", slot, line(fudge, 1, six));
		UUID expired = placeOrder(customer, "ONLINE", slot, line(fudge, 1, six));
		jdbc.update("UPDATE orders SET expires_at = now() - interval '1 minute' WHERE id = ?", expired);
		assertThat(expiry.expireDue()).isEqualTo(1);

		change(unpaid, "CONFIRMED", null).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(OrderAdminService.INVALID_TRANSITION))
			.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("verifying its payment")));
		change(unpaid, "PREPARING", null).andExpect(status().isConflict());
		change(expired, "CANCELLED", "Clean up").andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("reconciliation")));
		change(expired, "CONFIRMED", null).andExpect(status().isConflict());

		assertThat(orderStatus(unpaid)).isEqualTo("PENDING_PAYMENT");
		assertThat(orderStatus(expired)).isEqualTo("EXPIRED");
		assertThat(count("audit_events")).isZero();
	}

	// ------------------------------------------------------------------ cancellation

	@Test
	void anAdminCancelsWithAReasonEvenAfterTheCustomerDeadline() throws Exception {
		UUID orderId = placeOrder(customer, "COD", slot, line(fudge, 1, six));
		jdbc.update("UPDATE orders SET cancellation_deadline = now() - interval '1 hour' WHERE id = ?", orderId);

		change(orderId, "CANCELLED", null).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		change(orderId, "CANCELLED", "  ").andExpect(status().isUnprocessableContent());
		change(orderId, "CANCELLED", "Oven broke down").andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CANCELLED"))
			.andExpect(jsonPath("$.paymentStatus").value("CANCELLED"));

		assertThat(reservedUnits(slot)).isZero();
		assertThat(reservedStock(chocolate)).isEqualByComparingTo("0");
		assertThat(slotReservationStatus(orderId)).isEqualTo("RELEASED");
		assertThat(jdbc.queryForMap("""
				SELECT actor_type::text AS actor, actor_user_id, reason FROM order_status_history
				WHERE order_id = ? AND to_status = 'CANCELLED'""", orderId))
			.containsEntry("actor", "ADMIN")
			.containsEntry("actor_user_id", userIdOf(admin))
			.containsEntry("reason", "Oven broke down");
		assertThat(jdbc.queryForObject("SELECT reason FROM audit_events WHERE entity_id = ?", String.class, orderId))
			.isEqualTo("Oven broke down");
		assertThat(events(orderId)).containsExactly("order.placed", "order.cancelled");
	}

	@Test
	void cancellingDuringPreparationReleasesTheSlotButNotTheConsumedStock() throws Exception {
		UUID orderId = placeOrder(customer, "COD", slot, line(fudge, 1, six));
		change(orderId, "PREPARING", null).andExpect(status().isOk());
		change(orderId, "READY", null).andExpect(status().isOk());

		change(orderId, "CANCELLED", "Customer unreachable").andExpect(status().isOk());

		assertThat(reservedUnits(slot)).isZero();
		assertThat(slotReservationStatus(orderId)).isEqualTo("RELEASED");
		// The brownies were baked: the chocolate is gone.
		assertThat(stockReservationStatuses(orderId)).containsExactly("CONSUMED");
		assertThat(column(chocolate, "on_hand")).isEqualByComparingTo("900");
		assertThat(reservedStock(chocolate)).isEqualByComparingTo("0");
	}

	@Test
	void cancellingAPaidOrderOpensARefundRequestByTheAdmin() throws Exception {
		UUID orderId = placeOrder(customer, "MANUAL", slot, line(fudge, 1, six));
		jdbc.update("UPDATE payments SET status = 'SUCCEEDED' WHERE order_id = ?", orderId);
		jdbc.update("UPDATE orders SET status = 'CONFIRMED' WHERE id = ?", orderId);

		change(orderId, "CANCELLED", "Delivery area flooded").andExpect(status().isOk())
			.andExpect(jsonPath("$.paymentStatus").value("SUCCEEDED"));

		assertThat(jdbc.queryForMap("""
				SELECT status::text AS status, amount, request_reason, requested_by_type::text AS by_type, requested_by
				FROM refunds WHERE order_id = ?""", orderId))
			.containsEntry("status", "REQUESTED")
			.containsEntry("request_reason", "Delivery area flooded")
			.containsEntry("by_type", "ADMIN")
			.containsEntry("requested_by", userIdOf(admin))
			.satisfies(r -> assertThat((BigDecimal) r.get("amount")).isEqualByComparingTo("1310.00"));
	}

	@Test
	void ordersOutForDeliveryOrClosedCannotBeCancelled() throws Exception {
		UUID onTheWay = placeOrder(customer, "COD", slot, line(cookies, 1));
		for (String step : List.of("PREPARING", "READY", "OUT_FOR_DELIVERY")) {
			change(onTheWay, step, null).andExpect(status().isOk());
		}
		UUID cancelled = placeOrder(customer, "COD", slot, line(cookies, 1));
		cancel(customer, cancelled, null).andExpect(status().isOk());

		change(onTheWay, "CANCELLED", "Rider fell ill").andExpect(status().isConflict());
		change(cancelled, "CANCELLED", "Again").andExpect(status().isConflict());
		assertThat(orderStatus(onTheWay)).isEqualTo("OUT_FOR_DELIVERY");
	}

	// ------------------------------------------------------------------ views and access

	@Test
	void theAdminDetailAddsCustomerHistoryPaymentsAndRefunds() throws Exception {
		UUID orderId = placeOrder(customer, "MANUAL", slot, line(fudge, 1, six));
		jdbc.update("UPDATE payments SET status = 'SUCCEEDED' WHERE order_id = ?", orderId);
		jdbc.update("UPDATE orders SET status = 'CONFIRMED' WHERE id = ?", orderId);
		change(orderId, "CANCELLED", "Duplicate order").andExpect(status().isOk());
		String phone = jdbc.queryForObject("SELECT phone FROM users WHERE id = ?", String.class, userIdOf(customer));

		mockMvc.perform(get("/api/v1/admin/orders/{id}", orderId).header(HttpHeaders.AUTHORIZATION, admin))
			.andExpect(status().isOk())
			// OrderDetail fields, flattened (allOf in the contract).
			.andExpect(jsonPath("$.orderId").value(orderId.toString()))
			.andExpect(jsonPath("$.status").value("CANCELLED"))
			.andExpect(jsonPath("$.items[0].productName").value("Fudge Brownie Box"))
			.andExpect(jsonPath("$.delivery.phone").value("+8801712345678"))
			.andExpect(jsonPath("$.order").doesNotExist())
			// The account behind the order, not the delivery contact.
			.andExpect(jsonPath("$.customerUserId").value(userIdOf(customer).toString()))
			.andExpect(jsonPath("$.customerName").value("Test CUSTOMER"))
			.andExpect(jsonPath("$.customerPhone").value(phone))
			.andExpect(jsonPath("$.statusEvents[*].toStatus", contains("PENDING_PAYMENT", "CANCELLED")))
			.andExpect(jsonPath("$.statusEvents[1].actorType").value("ADMIN"))
			.andExpect(jsonPath("$.statusEvents[1].actorUserId").value(userIdOf(admin).toString()))
			.andExpect(jsonPath("$.statusEvents[1].reason").value("Duplicate order"))
			.andExpect(jsonPath("$.payments[0].method").value("MANUAL"))
			.andExpect(jsonPath("$.payments[0].status").value("SUCCEEDED"))
			.andExpect(jsonPath("$.refunds[0].status").value("REQUESTED"))
			.andExpect(jsonPath("$.refunds[0].requestedByType").value("ADMIN"))
			.andExpect(jsonPath("$.refunds[0].amount").value(1310.00));

		mockMvc.perform(get("/api/v1/admin/orders/{id}/status-history", orderId).header(HttpHeaders.AUTHORIZATION,
				admin))
			.andExpect(jsonPath("$[*].toStatus", contains("PENDING_PAYMENT", "CANCELLED")))
			.andExpect(jsonPath("$[0].actorType").value("CUSTOMER"))
			.andExpect(jsonPath("$[0].fromStatus").isEmpty());
	}

	@Test
	void theAdminListShowsEveryCustomersOrdersAndFiltersByStatus() throws Exception {
		UUID mine = placeOrder(customer, "COD", slot, line(cookies, 1));
		String other = bearer("CUSTOMER");
		UUID theirs = placeOrder(other, "MANUAL", slot, line(cookies, 1));

		mockMvc.perform(get("/api/v1/admin/orders").header(HttpHeaders.AUTHORIZATION, admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[*].orderId", contains(theirs.toString(), mine.toString())));
		mockMvc.perform(get("/api/v1/admin/orders").param("status", "PENDING_PAYMENT")
			.header(HttpHeaders.AUTHORIZATION, admin)).andExpect(jsonPath("$[*].orderId", contains(theirs.toString())));
		mockMvc.perform(get("/api/v1/admin/orders").param("status", "SHIPPED").header(HttpHeaders.AUTHORIZATION, admin))
			.andExpect(status().isBadRequest());
	}

	@Test
	void onlyAdminsMayUseTheseEndpoints() throws Exception {
		UUID orderId = placeOrder(customer, "COD", slot, line(cookies, 1));

		mockMvc.perform(get("/api/v1/admin/orders").header(HttpHeaders.AUTHORIZATION, customer))
			.andExpect(status().isForbidden());
		mockMvc.perform(get("/api/v1/admin/orders/{id}", orderId).header(HttpHeaders.AUTHORIZATION, customer))
			.andExpect(status().isForbidden());
		mockMvc.perform(get("/api/v1/admin/orders/{id}/status-history", orderId).header(HttpHeaders.AUTHORIZATION,
				customer)).andExpect(status().isForbidden());
		change(customer, orderId, "PREPARING", null).andExpect(status().isForbidden());
		assertThat(orderStatus(orderId)).isEqualTo("CONFIRMED");

		mockMvc.perform(get("/api/v1/admin/orders/{id}", UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, admin))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
		mockMvc.perform(get("/api/v1/admin/orders/{id}/status-history", UUID.randomUUID())
			.header(HttpHeaders.AUTHORIZATION, admin)).andExpect(status().isNotFound());
		change(UUID.randomUUID(), "PREPARING", null).andExpect(status().isNotFound());
		change(orderId, "SHIPPED", null).andExpect(status().isBadRequest());
	}

	// ------------------------------------------------------------------ notices and races

	@Test
	void dispatchingSendsTheOnItsWayNotice() throws Exception {
		UUID orderId = placeOrder(customer, "COD", slot, line(cookies, 1));
		for (String step : List.of("PREPARING", "READY", "OUT_FOR_DELIVERY")) {
			change(orderId, step, null).andExpect(status().isOk());
		}

		assertThat(dispatcher.dispatchDue()).isEqualTo(2);

		String number = jdbc.queryForObject("SELECT order_number FROM orders WHERE id = ?", String.class, orderId);
		assertThat(sms.sent()).extracting(SmsMessage::text)
			.last()
			.asString()
			.startsWith("Baked & Browned: order " + number + " is on its way. Delivery ");
	}

	@RepeatedTest(5)
	void preparationRacingACustomerCancellationNeverLosesStock() throws Exception {
		UUID adminId = userIdOf(admin);
		UUID customerId = userIdOf(customer);
		UUID orderId = placeOrder(customer, "COD", slot, line(fudge, 1, six));

		race(2, i -> () -> {
			try {
				if (i == 0) {
					admins.changeStatus(orderId, adminId, OrderStatus.PREPARING, null);
				}
				else {
					cancellations.cancelByCustomer(orderId, customerId, null);
				}
				return "OK";
			}
			catch (ApiException ex) {
				return ex.code();
			}
		});

		if ("PREPARING".equals(orderStatus(orderId))) {
			assertThat(column(chocolate, "on_hand")).isEqualByComparingTo("900");
			assertThat(reservedUnits(slot)).isEqualTo(2);
		}
		else {
			assertThat(orderStatus(orderId)).isEqualTo("CANCELLED");
			assertThat(column(chocolate, "on_hand")).isEqualByComparingTo("1000");
			assertThat(reservedUnits(slot)).isZero();
		}
		assertThat(reservedStock(chocolate)).isEqualByComparingTo("0");
	}

	// ------------------------------------------------------------------ helpers

	private ResultActions change(UUID orderId, String status, @Nullable String reason) throws Exception {
		return change(admin, orderId, status, reason);
	}

	private ResultActions change(String bearer, UUID orderId, String status, @Nullable String reason)
			throws Exception {
		String body = reason == null ? "{\"status\":\"" + status + "\"}"
				: "{\"status\":\"" + status + "\",\"reason\":\"" + reason + "\"}";
		return mockMvc.perform(patch("/api/v1/admin/orders/{id}/status", orderId).header(HttpHeaders.AUTHORIZATION, bearer)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

}
