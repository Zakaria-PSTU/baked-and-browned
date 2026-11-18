package com.bakedandbrowned.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.bakedandbrowned.common.value.Money;
import com.bakedandbrowned.ordering.CheckoutIntegrationTest;
import com.bakedandbrowned.ordering.service.HoldExpiry;
import com.bakedandbrowned.ordering.service.OrderEvents;
import com.bakedandbrowned.payment.controller.OnlinePaymentController;
import com.bakedandbrowned.payment.gateway.GatewayPaymentStatus;
import com.bakedandbrowned.payment.gateway.ProviderCallback;
import com.bakedandbrowned.payment.gateway.fake.FakePaymentGateway;
import com.bakedandbrowned.payment.service.GatewayResults;
import com.bakedandbrowned.payment.service.GatewayResults.Outcome;
import com.bakedandbrowned.payment.service.OnlinePaymentInitiation;

/**
 * ONLINE payments end to end against the fake gateway (ADR-0008, ADR-0012): hosted checkout,
 * server-to-server verified callbacks applied once, retries, late and mismatched payments, and the
 * race with hold expiry. Order total in these tests: 1250.00 + 60.00 delivery = 1310.00.
 */
class OnlinePaymentIntegrationTests extends CheckoutIntegrationTest {

	@Autowired
	private FakePaymentGateway gateway;

	@Autowired
	private GatewayResults results;

	@Autowired
	private HoldExpiry expiry;

	private String customer;

	private String admin;

	@BeforeEach
	void people() {
		customer = bearer("CUSTOMER");
		admin = bearer("ADMIN");
	}

	// ------------------------------------------------------------------ initiation

	@Test
	void initiationOpensAHostedSessionAndReusesItWhileOpen() throws Exception {
		UUID orderId = onlineOrder();

		String first = initiate(customer, orderId).andExpect(status().isOk())
			.andExpect(jsonPath("$.paymentId").value(currentPayment(orderId).toString()))
			.andExpect(jsonPath("$.checkoutUrl").value(org.hamcrest.Matchers.startsWith(
					"http://localhost:4200/dev/fake-gateway?session=fake_sess_")))
			.andReturn()
			.getResponse()
			.getContentAsString();
		Map<String, Object> payment = jdbc.queryForMap(
				"SELECT provider, provider_session_id, status::text AS status FROM payments WHERE order_id = ?", orderId);
		assertThat(payment).containsEntry("provider", "fake").containsEntry("status", "PENDING");
		assertThat((String) payment.get("provider_session_id")).startsWith("fake_sess_");

		String again = initiate(customer, orderId).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(again).isEqualTo(first);
		assertThat(count("payments")).isEqualTo(1);
		// Initiation alone never changes the order.
		assertThat(orderStatus(orderId)).isEqualTo("PENDING_PAYMENT");
	}

	@Test
	void onlyTheCustomerMayInitiateAnOnlineOrderThatIsStillPayable() throws Exception {
		UUID orderId = onlineOrder();
		UUID cod = placeOrder(customer, "COD", slot, line(fudge, 1, six));
		UUID overdue = onlineOrder();
		jdbc.update("UPDATE orders SET expires_at = now() - interval '1 minute' WHERE id = ?", overdue);

		initiate(bearer("CUSTOMER"), orderId).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
		initiate(admin, orderId).andExpect(status().isNotFound());
		mockMvc.perform(post("/api/v1/payments/{id}/initiate", orderId)).andExpect(status().isUnauthorized());
		initiate(customer, UUID.randomUUID()).andExpect(status().isNotFound());
		initiate(customer, cod).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(OnlinePaymentInitiation.NOT_AN_ONLINE_PAYMENT));
		initiate(customer, overdue).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(OnlinePaymentInitiation.PAYMENT_HOLD_EXPIRED));
		cancel(customer, orderId, null).andExpect(status().isOk());
		initiate(customer, orderId).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(OnlinePaymentInitiation.INVALID_STATE));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE provider_session_id IS NOT NULL",
				Integer.class)).isZero();
	}

	// ------------------------------------------------------------------ callbacks

	@Test
	void aVerifiedSuccessConfirmsTheOrderExactlyOnce() throws Exception {
		UUID orderId = onlineOrder();
		String session = startSession(orderId);
		gateway.settle(session, GatewayPaymentStatus.SUCCEEDED);

		webhook(gateway.callbackFor(session, GatewayPaymentStatus.SUCCEEDED)).andExpect(status().isOk());

		assertThat(orderStatus(orderId)).isEqualTo("CONFIRMED");
		assertThat(jdbc.queryForMap("""
				SELECT status::text AS status, provider_transaction_id, captured_at IS NOT NULL AS captured,
				       verified_by FROM payments WHERE order_id = ?""", orderId))
			.containsEntry("status", "SUCCEEDED")
			.containsEntry("provider_transaction_id", "fake_txn_" + session.substring("fake_sess_".length()))
			.containsEntry("captured", true)
			.containsEntry("verified_by", null);
		assertThat(slotReservationStatus(orderId)).isEqualTo("CONFIRMED");
		assertThat(stockReservationStatuses(orderId)).containsOnly("CONFIRMED");
		assertThat(jdbc.queryForList("""
				SELECT actor_type::text FROM order_status_history WHERE order_id = ? AND to_status = 'CONFIRMED'""",
				String.class, orderId)).containsExactly("GATEWAY");
		assertThat(events(orderId)).containsExactly(OrderEvents.PLACED, OrderEvents.CONFIRMED);
		assertThat(jdbc.queryForList("SELECT actor_type::text || ' ' || action FROM audit_events WHERE entity_id = ?",
				String.class, currentPayment(orderId))).containsExactly("GATEWAY payment.gateway_confirmed");

		// The provider retries the same notice, and the browser's "success" redirect is not an input at all.
		webhook(gateway.callbackFor(session, GatewayPaymentStatus.SUCCEEDED)).andExpect(status().isOk());
		assertThat(results.apply(gateway.queryStatus(session))).isEqualTo(Outcome.DUPLICATE);
		assertThat(count("payment_webhook_events")).isEqualTo(1);
		assertThat(events(orderId)).containsExactly(OrderEvents.PLACED, OrderEvents.CONFIRMED);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM order_status_history WHERE order_id = ?", Integer.class,
				orderId)).isEqualTo(2);
		// Collected today now includes the online payment.
		mockMvc.perform(get("/api/v1/admin/dashboard/summary").header(HttpHeaders.AUTHORIZATION, admin))
			.andExpect(jsonPath("$.collectedToday").value(1310.00));
	}

	@Test
	void aCallbackCannotClaimWhatTheProviderDidNotRecord() throws Exception {
		UUID orderId = onlineOrder();
		String session = startSession(orderId);

		// Signed, but the provider still has the session as PENDING.
		webhook(gateway.callbackFor(session, GatewayPaymentStatus.SUCCEEDED)).andExpect(status().isOk());
		assertThat(orderStatus(orderId)).isEqualTo("PENDING_PAYMENT");
		assertThat(paymentStatus(orderId)).isEqualTo("PENDING");

		// Forged and tampered callbacks change nothing and are not recorded.
		ProviderCallback signed = gateway.callbackFor(session, GatewayPaymentStatus.SUCCEEDED);
		gateway.settle(session, GatewayPaymentStatus.SUCCEEDED);
		mockMvc.perform(post("/api/v1/payments/webhooks/fake").contentType(MediaType.APPLICATION_JSON)
				.header(FakePaymentGateway.SIGNATURE_HEADER, "00".repeat(32))
				.content(signed.body()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(OnlinePaymentController.CALLBACK_NOT_VERIFIED));
		mockMvc.perform(post("/api/v1/payments/webhooks/fake").contentType(MediaType.APPLICATION_JSON)
				.content(signed.body()))
			.andExpect(status().isBadRequest());
		mockMvc.perform(post("/api/v1/payments/webhooks/nobody").contentType(MediaType.APPLICATION_JSON)
				.content(signed.body()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(OnlinePaymentController.UNKNOWN_PROVIDER));
		assertThat(orderStatus(orderId)).isEqualTo("PENDING_PAYMENT");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_webhook_events WHERE event_id LIKE '%SUCCEEDED'",
				Integer.class)).isZero();
	}

	@Test
	void aFailedAttemptCanBeRetriedWithANewPaymentWithinTheHold() throws Exception {
		UUID orderId = onlineOrder();
		UUID firstPayment = currentPayment(orderId);
		String first = startSession(orderId);
		gateway.settle(first, GatewayPaymentStatus.FAILED);

		webhook(gateway.callbackFor(first, GatewayPaymentStatus.FAILED)).andExpect(status().isOk());

		assertThat(orderStatus(orderId)).isEqualTo("PENDING_PAYMENT");
		assertThat(jdbc.queryForObject("SELECT status::text FROM payments WHERE id = ?", String.class, firstPayment))
			.isEqualTo("FAILED");
		assertThat(slotReservationStatus(orderId)).isEqualTo("HELD");

		initiate(customer, orderId).andExpect(status().isOk());
		UUID retry = currentPayment(orderId);
		assertThat(retry).isNotEqualTo(firstPayment);
		assertThat(jdbc.queryForObject("SELECT superseded_at IS NOT NULL FROM payments WHERE id = ?", Boolean.class,
				firstPayment)).isTrue();
		String second = sessionOf(retry);
		assertThat(second).isNotEqualTo(first);

		gateway.settle(second, GatewayPaymentStatus.SUCCEEDED);
		webhook(gateway.callbackFor(second, GatewayPaymentStatus.SUCCEEDED)).andExpect(status().isOk());
		assertThat(orderStatus(orderId)).isEqualTo("CONFIRMED");
		assertThat(paymentStatus(orderId)).isEqualTo("SUCCEEDED");

		// Should the replaced attempt ever report money too, an ADMIN looks at it; nothing changes.
		gateway.settle(first, GatewayPaymentStatus.SUCCEEDED);
		assertThat(results.apply(gateway.queryStatus(first))).isEqualTo(Outcome.AMBIGUOUS);
		assertThat(caseTypes(orderId)).containsExactly("AMBIGUOUS_STATUS");
		assertThat(jdbc.queryForObject("SELECT status::text FROM payments WHERE id = ?", String.class, firstPayment))
			.isEqualTo("FAILED");
	}

	@Test
	void aCancelledHostedPageEndsTheAttemptOnly() throws Exception {
		UUID orderId = onlineOrder();
		String session = startSession(orderId);
		gateway.settle(session, GatewayPaymentStatus.CANCELLED);

		webhook(gateway.callbackFor(session, GatewayPaymentStatus.CANCELLED)).andExpect(status().isOk());

		assertThat(paymentStatus(orderId)).isEqualTo("CANCELLED");
		assertThat(orderStatus(orderId)).isEqualTo("PENDING_PAYMENT");
		// The hold then runs out as usual and releases everything once.
		jdbc.update("UPDATE orders SET expires_at = now() - interval '1 minute' WHERE id = ?", orderId);
		assertThat(expiry.expire(orderId)).isTrue();
		assertThat(orderStatus(orderId)).isEqualTo("EXPIRED");
		assertThat(slotReservationStatus(orderId)).isEqualTo("RELEASED");
	}

	@Test
	void aSuccessForTheWrongAmountChangesNothingAndOpensACase() throws Exception {
		UUID orderId = onlineOrder();
		String session = startSession(orderId);
		gateway.settle(session, GatewayPaymentStatus.SUCCEEDED, Money.of("13.10"));

		webhook(gateway.callbackFor(session, GatewayPaymentStatus.SUCCEEDED)).andExpect(status().isOk());

		assertThat(orderStatus(orderId)).isEqualTo("PENDING_PAYMENT");
		assertThat(paymentStatus(orderId)).isEqualTo("PENDING");
		assertThat(caseTypes(orderId)).containsExactly("AMOUNT_MISMATCH");
		assertThat(jdbc.queryForObject("""
				SELECT details->>'reportedAmount' || '/' || (details->>'expectedAmount')
				FROM payment_reconciliation_cases WHERE order_id = ?""", String.class, orderId))
			.isEqualTo("13.10/1310.00");
	}

	@Test
	void aSuccessAfterExpiryIsRecordedButNeverConfirmsTheOrder() throws Exception {
		UUID orderId = onlineOrder();
		String session = startSession(orderId);
		jdbc.update("UPDATE orders SET expires_at = now() - interval '1 minute' WHERE id = ?", orderId);
		assertThat(expiry.expire(orderId)).isTrue();
		BigDecimalStock before = stock();

		gateway.settle(session, GatewayPaymentStatus.SUCCEEDED);
		webhook(gateway.callbackFor(session, GatewayPaymentStatus.SUCCEEDED)).andExpect(status().isOk());

		assertThat(orderStatus(orderId)).isEqualTo("EXPIRED");
		assertThat(paymentStatus(orderId)).isEqualTo("SUCCEEDED");
		assertThat(caseTypes(orderId)).containsExactly("LATE_SUCCESS");
		assertThat(slotReservationStatus(orderId)).isEqualTo("RELEASED");
		assertThat(stock()).isEqualTo(before);
		mockMvc.perform(get("/api/v1/admin/dashboard/summary").header(HttpHeaders.AUTHORIZATION, admin))
			.andExpect(jsonPath("$.openReconciliationCases").value(1));
	}

	@Test
	void aSuccessAfterTheCustomerCancelledOpensALateSuccessCase() throws Exception {
		UUID orderId = onlineOrder();
		String session = startSession(orderId);
		cancel(customer, orderId, null).andExpect(status().isOk());

		gateway.settle(session, GatewayPaymentStatus.SUCCEEDED);
		webhook(gateway.callbackFor(session, GatewayPaymentStatus.SUCCEEDED)).andExpect(status().isOk());

		assertThat(orderStatus(orderId)).isEqualTo("CANCELLED");
		assertThat(paymentStatus(orderId)).isEqualTo("SUCCEEDED");
		assertThat(caseTypes(orderId)).containsExactly("LATE_SUCCESS");
	}

	/**
	 * Expiry and a verified success arrive together after the hold ran out. Whoever locks the order
	 * first wins: either the order is confirmed with its holds intact, or it expires and the money
	 * becomes a LATE_SUCCESS case. Never both, and holds are released at most once.
	 */
	@RepeatedTest(10)
	void aSuccessRacingHoldExpiryHasExactlyOneWinner() throws Exception {
		UUID orderId = onlineOrder();
		String session = startSession(orderId);
		gateway.settle(session, GatewayPaymentStatus.SUCCEEDED);
		jdbc.update("UPDATE orders SET expires_at = now() - interval '1 second' WHERE id = ?", orderId);

		List<Object> outcomes = race(2, i -> i == 0 ? () -> results.apply(gateway.queryStatus(session))
				: () -> expiry.expire(orderId));

		assertThat(paymentStatus(orderId)).isEqualTo("SUCCEEDED");
		if (outcomes.get(0) == Outcome.CONFIRMED) {
			assertThat(outcomes.get(1)).isEqualTo(false);
			assertThat(orderStatus(orderId)).isEqualTo("CONFIRMED");
			assertThat(slotReservationStatus(orderId)).isEqualTo("CONFIRMED");
			assertThat(caseTypes(orderId)).isEmpty();
		}
		else {
			assertThat(outcomes).containsExactly(Outcome.LATE_SUCCESS, true);
			assertThat(orderStatus(orderId)).isEqualTo("EXPIRED");
			assertThat(slotReservationStatus(orderId)).isEqualTo("RELEASED");
			assertThat(caseTypes(orderId)).containsExactly("LATE_SUCCESS");
		}
		assertThat(reservedUnits(slot)).isEqualTo("CONFIRMED".equals(orderStatus(orderId)) ? 2 : 0);
	}

	// ------------------------------------------------------------------ helpers

	private UUID onlineOrder() throws Exception {
		return placeOrder(customer, "ONLINE", slot, line(fudge, 1, six));
	}

	private ResultActions initiate(String bearer, UUID orderId) throws Exception {
		return mockMvc.perform(post("/api/v1/payments/{id}/initiate", orderId).header(HttpHeaders.AUTHORIZATION, bearer));
	}

	private String startSession(UUID orderId) throws Exception {
		initiate(customer, orderId).andExpect(status().isOk());
		return sessionOf(currentPayment(orderId));
	}

	private ResultActions webhook(ProviderCallback callback) throws Exception {
		return mockMvc.perform(post("/api/v1/payments/webhooks/{provider}", callback.provider())
			.contentType(MediaType.APPLICATION_JSON)
			.header(FakePaymentGateway.SIGNATURE_HEADER, callback.header(FakePaymentGateway.SIGNATURE_HEADER))
			.content(callback.body()));
	}

	private UUID currentPayment(UUID orderId) {
		return jdbc.queryForObject("SELECT id FROM payments WHERE order_id = ? AND superseded_at IS NULL", UUID.class,
				orderId);
	}

	private String sessionOf(UUID paymentId) {
		return jdbc.queryForObject("SELECT provider_session_id FROM payments WHERE id = ?", String.class, paymentId);
	}

	private List<String> caseTypes(UUID orderId) {
		return jdbc.queryForList(
				"SELECT case_type::text FROM payment_reconciliation_cases WHERE order_id = ? AND status = 'OPEN'",
				String.class, orderId);
	}

	private BigDecimalStock stock() {
		return new BigDecimalStock(column(chocolate, "on_hand").toPlainString(), reservedStock(chocolate).toPlainString(),
				reservedUnits(slot));
	}

	private record BigDecimalStock(String onHand, String reserved, int slotUnits) {
	}

}
