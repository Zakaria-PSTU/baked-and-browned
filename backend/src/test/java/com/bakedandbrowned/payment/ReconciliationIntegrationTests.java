package com.bakedandbrowned.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.bakedandbrowned.common.value.Money;
import com.bakedandbrowned.inventory.service.StockReservations;
import com.bakedandbrowned.ordering.CheckoutIntegrationTest;
import com.bakedandbrowned.ordering.service.HoldExpiry;
import com.bakedandbrowned.ordering.service.OrderEvents;
import com.bakedandbrowned.ordering.service.OrderPaymentOutcomes;
import com.bakedandbrowned.payment.gateway.GatewayPaymentStatus;
import com.bakedandbrowned.payment.gateway.VerifiedPaymentResult;
import com.bakedandbrowned.payment.gateway.fake.FakePaymentGateway;
import com.bakedandbrowned.payment.service.GatewayResults;
import com.bakedandbrowned.payment.service.GatewayResults.Outcome;
import com.bakedandbrowned.payment.service.PaymentReconciliation;
import com.bakedandbrowned.payment.service.ReconciliationCases;
import com.bakedandbrowned.payment.service.ReconciliationJob;

/**
 * Phase 3C: the reconciliation worker (missed and late callbacks, backoff, claiming, unresolved
 * payments) and ADMIN case resolution (REINSTATE under fresh locks, REQUEST_REFUND, NO_ACTION).
 * Order total in these tests: 1250.00 + 60.00 delivery = 1310.00; it reserves 2 slot units and
 * 100 g of the 1000 g of chocolate.
 */
class ReconciliationIntegrationTests extends CheckoutIntegrationTest {

	@Autowired
	private FakePaymentGateway gateway;

	@Autowired
	private GatewayResults results;

	@Autowired
	private PaymentReconciliation reconciliation;

	@Autowired
	private ReconciliationJob job;

	@Autowired
	private HoldExpiry expiry;

	private String customer;

	private String admin;

	@BeforeEach
	void people() {
		customer = bearer("CUSTOMER");
		admin = bearer("ADMIN");
	}

	// ------------------------------------------------------------------ worker

	@Test
	void aMissedCallbackIsCaughtByAskingTheGateway() throws Exception {
		UUID orderId = onlineOrder();
		String session = startSession(orderId);
		gateway.settle(session, GatewayPaymentStatus.SUCCEEDED);
		// No callback arrives. Too fresh for the worker at first: the callback gets its chance.
		assertThat(job.reconcileDue()).isZero();
		age(orderId, "3 minutes");

		assertThat(job.reconcileDue()).isEqualTo(1);

		assertThat(orderStatus(orderId)).isEqualTo("CONFIRMED");
		assertThat(paymentStatus(orderId)).isEqualTo("SUCCEEDED");
		assertThat(jdbc.queryForMap(
				"SELECT gateway_checked_at IS NOT NULL AS checked, gateway_check_error FROM payments WHERE order_id = ?",
				orderId))
			.containsEntry("checked", true)
			.containsEntry("gateway_check_error", null);
		// Settled payments are no longer candidates.
		assertThat(reconciliation.due(10)).isEmpty();
	}

	@Test
	void aLateSuccessAfterExpiryIsCaughtAndBecomesACase() throws Exception {
		UUID orderId = expiredOrderPaidLate();

		assertThat(orderStatus(orderId)).isEqualTo("EXPIRED");
		assertThat(paymentStatus(orderId)).isEqualTo("SUCCEEDED");
		assertThat(openCases(orderId)).containsExactly("LATE_SUCCESS");
	}

	@Test
	void checksBackOffAsAPaymentAges() throws Exception {
		UUID orderId = onlineOrder();
		startSession(orderId);
		UUID paymentId = paymentOf(orderId);
		age(orderId, "4 hours");
		assertThat(reconciliation.reconcile(paymentId)).contains(Outcome.NO_CHANGE);
		// Just checked: not due, even past the 5-minute minimum, until a quarter of its age (1 hour).
		jdbc.update("UPDATE payments SET gateway_checked_at = now() - interval '30 minutes' WHERE id = ?", paymentId);
		assertThat(reconciliation.due(10)).isEmpty();
		assertThat(reconciliation.reconcile(paymentId)).isEmpty();
		jdbc.update("UPDATE payments SET gateway_checked_at = now() - interval '61 minutes' WHERE id = ?", paymentId);
		assertThat(reconciliation.due(10)).containsExactly(paymentId);
		// Pending again: the same provider state, applied once.
		assertThat(reconciliation.reconcile(paymentId)).contains(Outcome.DUPLICATE);
	}

	@Test
	void concurrentWorkersNeverAskAboutTheSamePaymentTwice() throws Exception {
		UUID orderId = onlineOrder();
		String session = startSession(orderId);
		gateway.settle(session, GatewayPaymentStatus.SUCCEEDED);
		age(orderId, "3 minutes");
		UUID paymentId = paymentOf(orderId);

		List<Optional<Outcome>> outcomes = race(4, i -> () -> reconciliation.reconcile(paymentId));

		assertThat(outcomes).filteredOn(Optional::isPresent).hasSize(1);
		assertThat(outcomes).filteredOn(Optional::isPresent).first().isEqualTo(Optional.of(Outcome.CONFIRMED));
		assertThat(events(orderId)).containsExactly(OrderEvents.PLACED, OrderEvents.CONFIRMED);
	}

	@Test
	void aPaymentTheGatewayNeverAnsweredAboutBecomesOneCase() throws Exception {
		UUID orderId = onlineOrder();
		startSession(orderId);
		UUID paymentId = paymentOf(orderId);
		// The provider no longer knows the session (or is unreachable): the check fails.
		jdbc.update("UPDATE payments SET provider_session_id = 'fake_sess_lost' WHERE id = ?", paymentId);
		age(orderId, "3 minutes");

		assertThat(reconciliation.reconcile(paymentId)).isEmpty();
		assertThat(jdbc.queryForObject("SELECT gateway_check_error FROM payments WHERE id = ?", String.class,
				paymentId)).contains("Unknown fake gateway session");
		assertThat(reconciliation.closeOutUnresolved(10)).isZero();

		age(orderId, "49 hours");
		assertThat(reconciliation.due(10)).isEmpty();
		assertThat(reconciliation.closeOutUnresolved(10)).isEqualTo(1);
		assertThat(openCases(orderId)).containsExactly("AMBIGUOUS_STATUS");
		assertThat(caseReason(orderId)).isEqualTo("GATEWAY_STATUS_UNKNOWN");

		// Once only, even after an ADMIN resolves it.
		resolve(admin, caseOf(orderId), "NO_ACTION", "Checked with the provider: never paid.").andExpect(status().isOk());
		assertThat(reconciliation.closeOutUnresolved(10)).isZero();
	}

	@Test
	void anAbandonedCheckoutNeverBecomesACase() throws Exception {
		UUID orderId = onlineOrder();
		startSession(orderId);
		age(orderId, "3 minutes");
		assertThat(reconciliation.reconcile(paymentOf(orderId))).contains(Outcome.NO_CHANGE);

		age(orderId, "49 hours");
		assertThat(reconciliation.closeOutUnresolved(10)).isZero();
		assertThat(openCases(orderId)).isEmpty();
	}

	// ------------------------------------------------------------------ case review

	@Test
	void casesAreListedForAdminsOnlyOpenOldestFirst() throws Exception {
		UUID first = expiredOrderPaidLate();
		UUID second = expiredOrderPaidLate();
		jdbc.update("UPDATE payment_reconciliation_cases SET created_at = now() - interval '1 hour' WHERE order_id = ?",
				first);

		mockMvc.perform(get("/api/v1/admin/payment-reconciliation-cases").param("status", "OPEN")
				.header(HttpHeaders.AUTHORIZATION, admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].orderId").value(first.toString()))
			.andExpect(jsonPath("$[0].caseType").value("LATE_SUCCESS"))
			.andExpect(jsonPath("$[0].reason").value("PAID_AFTER_ORDER_EXPIRED"))
			.andExpect(jsonPath("$[0].status").value("OPEN"))
			.andExpect(jsonPath("$[0].orderNumber").isString())
			.andExpect(jsonPath("$[1].orderId").value(second.toString()));
		mockMvc.perform(get("/api/v1/admin/payment-reconciliation-cases").param("status", "RESOLVED")
				.header(HttpHeaders.AUTHORIZATION, admin))
			.andExpect(jsonPath("$.length()").value(0));
		mockMvc.perform(get("/api/v1/admin/payment-reconciliation-cases").header(HttpHeaders.AUTHORIZATION, customer))
			.andExpect(status().isForbidden());
		resolve(customer, caseOf(first), "NO_ACTION", "Not mine to decide").andExpect(status().isForbidden());
	}

	@Test
	void reinstatingALatePaidOrderReservesItsSlotAndStockAgain() throws Exception {
		UUID orderId = expiredOrderPaidLate();
		UUID caseId = caseOf(orderId);
		assertThat(reservedUnits(slot)).isZero();
		assertThat(reservedStock(chocolate)).isEqualByComparingTo("0");

		resolve(admin, caseId, "REINSTATE", "Customer confirmed; slot still has room.").andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("RESOLVED"))
			.andExpect(jsonPath("$.resolution").value("REINSTATED"))
			.andExpect(jsonPath("$.resolutionNote").value("Customer confirmed; slot still has room."))
			.andExpect(jsonPath("$.resolvedAt").isString());

		assertThat(orderStatus(orderId)).isEqualTo("CONFIRMED");
		assertThat(reservedUnits(slot)).isEqualTo(2);
		assertThat(slotReservationStatus(orderId)).isEqualTo("CONFIRMED");
		assertThat(reservedStock(chocolate)).isEqualByComparingTo("100");
		assertThat(stockReservationStatuses(orderId)).containsOnly("CONFIRMED");
		assertThat(jdbc.queryForMap("""
				SELECT actor_type::text AS actor, reason FROM order_status_history
				WHERE order_id = ? AND from_status = 'EXPIRED' AND to_status = 'CONFIRMED'""", orderId))
			.containsEntry("actor", "ADMIN")
			.containsEntry("reason", "Customer confirmed; slot still has room.");
		assertThat(events(orderId)).containsExactly(OrderEvents.PLACED, OrderEvents.EXPIRED, OrderEvents.CONFIRMED);
		assertThat(jdbc.queryForObject(
				"SELECT details->>'resolution' FROM audit_events WHERE action = 'reconciliation.resolved' AND entity_id = ?",
				String.class, caseId)).isEqualTo("REINSTATED");

		resolve(admin, caseId, "NO_ACTION", "Again").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(ReconciliationCases.CASE_ALREADY_RESOLVED));
	}

	@Test
	void reinstatingFailsWithoutChangesWhenTheSlotOrStockIsGone() throws Exception {
		UUID orderId = expiredOrderPaidLate();
		UUID caseId = caseOf(orderId);

		jdbc.update("UPDATE delivery_slots SET reserved_units = capacity_units WHERE id = ?", slot);
		resolve(admin, caseId, "REINSTATE", "Try to reinstate").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(OrderPaymentOutcomes.SLOT_UNAVAILABLE));
		jdbc.update("UPDATE delivery_slots SET reserved_units = 0 WHERE id = ?", slot);

		jdbc.update("UPDATE ingredients SET on_hand = 50 WHERE id = ?", chocolate);
		resolve(admin, caseId, "REINSTATE", "Try to reinstate").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(StockReservations.INSUFFICIENT_STOCK));

		// Each attempt rolled back completely: the slot units taken before the stock check too.
		assertThat(orderStatus(orderId)).isEqualTo("EXPIRED");
		assertThat(reservedUnits(slot)).isZero();
		assertThat(slotReservationStatus(orderId)).isEqualTo("RELEASED");
		assertThat(reservedStock(chocolate)).isEqualByComparingTo("0");
		assertThat(openCases(orderId)).containsExactly("LATE_SUCCESS");
	}

	@Test
	void anOrderHeldForReviewIsConfirmedWithTheHoldsItStillHas() throws Exception {
		UUID orderId = onlineOrder();
		String session = startSession(orderId);
		gateway.settle(session, GatewayPaymentStatus.SUCCEEDED);
		VerifiedPaymentResult v = gateway.queryStatus(session);
		assertThat(results.apply(new VerifiedPaymentResult(v.provider(), v.eventId(), v.sessionId(), v.transactionId(),
				v.orderReference(), v.amount(), v.currency(), v.status(), true))).isEqualTo(Outcome.HELD_FOR_REVIEW);

		resolve(admin, caseOf(orderId), "REINSTATE", "Customer verified by phone.").andExpect(status().isOk());

		assertThat(orderStatus(orderId)).isEqualTo("CONFIRMED");
		assertThat(slotReservationStatus(orderId)).isEqualTo("CONFIRMED");
		assertThat(reservedUnits(slot)).isEqualTo(2);
	}

	@Test
	void aCancelledOrdersLatePaymentIsRefundedNotReinstated() throws Exception {
		UUID orderId = onlineOrder();
		String session = startSession(orderId);
		cancel(customer, orderId, null).andExpect(status().isOk());
		gateway.settle(session, GatewayPaymentStatus.SUCCEEDED);
		assertThat(results.apply(gateway.queryStatus(session))).isEqualTo(Outcome.LATE_SUCCESS);
		UUID caseId = caseOf(orderId);

		resolve(admin, caseId, "REINSTATE", "Customer changed their mind").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(ReconciliationCases.REINSTATE_NOT_ALLOWED));
		resolve(admin, caseId, "REQUEST_REFUND", "Paid after cancelling; refund in full.").andExpect(status().isOk())
			.andExpect(jsonPath("$.resolution").value("REFUND_REQUESTED"));

		assertThat(jdbc.queryForMap("SELECT status::text AS status, amount, requested_by FROM refunds WHERE order_id = ?",
				orderId))
			.containsEntry("status", "REQUESTED")
			.containsEntry("requested_by", userIdOf(admin))
			.satisfies(r -> assertThat(Money.of((java.math.BigDecimal) r.get("amount"))).isEqualTo(Money.of("1310.00")));
		assertThat(orderStatus(orderId)).isEqualTo("CANCELLED");
	}

	@Test
	void aMismatchCanOnlyBeClosedAfterCheckingWithTheProvider() throws Exception {
		UUID orderId = onlineOrder();
		String session = startSession(orderId);
		gateway.settle(session, GatewayPaymentStatus.SUCCEEDED, Money.of("13.10"));
		assertThat(results.apply(gateway.queryStatus(session))).isEqualTo(Outcome.AMOUNT_MISMATCH);
		UUID caseId = caseOf(orderId);

		resolve(admin, caseId, "REINSTATE", "Looks fine").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(ReconciliationCases.REINSTATE_NOT_ALLOWED));
		resolve(admin, caseId, "REQUEST_REFUND", "Refund it").andExpect(status().isConflict());
		resolve(admin, caseId, "NO_ACTION", "Refunded 13.10 at the provider.").andExpect(status().isOk());
		assertThat(openCases(orderId)).isEmpty();
	}

	@Test
	void resolutionsNeedANoteAndAKnownCase() throws Exception {
		UUID orderId = expiredOrderPaidLate();

		resolve(admin, caseOf(orderId), "NO_ACTION", "ok").andExpect(status().isUnprocessableContent());
		mockMvc.perform(post("/api/v1/admin/payment-reconciliation-cases/{id}/resolution", caseOf(orderId))
				.header(HttpHeaders.AUTHORIZATION, admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"action\":\"FORGIVE\",\"note\":\"Not an action\"}"))
			.andExpect(status().is4xxClientError());
		resolve(admin, UUID.randomUUID(), "NO_ACTION", "No such case").andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value(ReconciliationCases.CASE_NOT_FOUND));
		assertThat(openCases(orderId)).containsExactly("LATE_SUCCESS");
	}

	// ------------------------------------------------------------------ helpers

	private UUID onlineOrder() throws Exception {
		return placeOrder(customer, "ONLINE", slot, line(fudge, 1, six));
	}

	private String startSession(UUID orderId) throws Exception {
		mockMvc.perform(post("/api/v1/payments/{id}/initiate", orderId).header(HttpHeaders.AUTHORIZATION, customer))
			.andExpect(status().isOk());
		return jdbc.queryForObject("SELECT provider_session_id FROM payments WHERE id = ?", String.class,
				paymentOf(orderId));
	}

	/** An ONLINE order that expired; then the provider records the success and the worker finds it. */
	private UUID expiredOrderPaidLate() throws Exception {
		UUID orderId = onlineOrder();
		String session = startSession(orderId);
		jdbc.update("UPDATE orders SET expires_at = now() - interval '1 minute' WHERE id = ?", orderId);
		assertThat(expiry.expire(orderId)).isTrue();
		gateway.settle(session, GatewayPaymentStatus.SUCCEEDED);
		age(orderId, "20 minutes");
		assertThat(reconciliation.reconcile(paymentOf(orderId))).contains(Outcome.LATE_SUCCESS);
		return orderId;
	}

	private void age(UUID orderId, String interval) {
		jdbc.update("UPDATE payments SET created_at = now() - ?::interval, gateway_checked_at = NULL WHERE order_id = ?",
				interval, orderId);
	}

	private UUID paymentOf(UUID orderId) {
		return jdbc.queryForObject("SELECT id FROM payments WHERE order_id = ? AND superseded_at IS NULL", UUID.class,
				orderId);
	}

	private UUID caseOf(UUID orderId) {
		return jdbc.queryForObject("SELECT id FROM payment_reconciliation_cases WHERE order_id = ? ORDER BY created_at LIMIT 1",
				UUID.class, orderId);
	}

	private String caseReason(UUID orderId) {
		return jdbc.queryForObject("SELECT details->>'reason' FROM payment_reconciliation_cases WHERE order_id = ?",
				String.class, orderId);
	}

	private List<String> openCases(UUID orderId) {
		return jdbc.queryForList(
				"SELECT case_type::text FROM payment_reconciliation_cases WHERE order_id = ? AND status = 'OPEN'",
				String.class, orderId);
	}

	private ResultActions resolve(String bearer, UUID caseId, String action, String note) throws Exception {
		return mockMvc.perform(post("/api/v1/admin/payment-reconciliation-cases/{id}/resolution", caseId)
			.header(HttpHeaders.AUTHORIZATION, bearer)
			.contentType(MediaType.APPLICATION_JSON)
			.content(json.writeValueAsString(Map.of("action", action, "note", note))));
	}

}
