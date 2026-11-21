package com.bakedandbrowned.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import com.bakedandbrowned.common.outbox.OutboxDispatcher;
import com.bakedandbrowned.common.value.Money;
import com.bakedandbrowned.common.web.ApiException;
import com.bakedandbrowned.notification.sms.FakeSmsSender;
import com.bakedandbrowned.payment.dto.PaymentDtos.PaymentVerificationRequest.Decision;
import com.bakedandbrowned.payment.service.RefundWorkflow;

/**
 * The refund workflow (ADR-0011): REQUESTED → APPROVED/REJECTED → COMPLETED, payment REFUNDED only
 * on completion, every step by an ADMIN with a note or reference, audited, and never twice.
 */
class RefundWorkflowIntegrationTests extends ManualPaymentIntegrationTest {

	@Autowired
	private RefundWorkflow workflow;

	@Autowired
	private OutboxDispatcher dispatcher;

	@Autowired
	private FakeSmsSender sms;

	@Autowired
	private TransactionTemplate transactions;

	private String admin;

	/** A second ADMIN, for approving refunds the first one requested. */
	private String reviewer;

	private String customer;

	@BeforeEach
	void people() {
		admin = bearer("ADMIN");
		reviewer = bearer("ADMIN");
		customer = bearer("CUSTOMER");
	}

	@Test
	void aCancelledPaidOrderIsRefundedThroughApprovalAndCompletion() throws Exception {
		UUID orderId = paidOrder();
		cancel(customer, orderId, null).andExpect(status().isOk());
		UUID refundId = onlyRefund(orderId);

		mockMvc.perform(get("/api/v1/admin/refunds").param("status", "REQUESTED")
			.header(HttpHeaders.AUTHORIZATION, admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[*].id", contains(refundId.toString())))
			.andExpect(jsonPath("$[0].requestedByType").value("CUSTOMER"))
			// v1.7: who asked (the customer who cancelled) and for which order.
			.andExpect(jsonPath("$[0].requestedBy").value(userIdOf(customer).toString()))
			.andExpect(jsonPath("$[0].orderNumber").value(jdbc.queryForObject(
					"SELECT order_number FROM orders WHERE id = ?", String.class, orderId)))
			.andExpect(jsonPath("$[0].amount").value(1310.00));

		decideRefund(refundId, "APPROVE", "Customer cancelled in time").andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("APPROVED"))
			.andExpect(jsonPath("$.decidedBy").value(userIdOf(admin).toString()))
			.andExpect(jsonPath("$.decisionNote").value("Customer cancelled in time"))
			.andExpect(jsonPath("$.decidedAt").isNotEmpty());
		// Approved is not refunded yet.
		assertThat(paymentStatus(orderId)).isEqualTo("SUCCEEDED");
		customerView(orderId).andExpect(jsonPath("$.payment.refundStatus").value("APPROVED"))
			.andExpect(jsonPath("$.paymentStatus").value("SUCCEEDED"));

		complete(refundId, "  BKASH-RF-77120  ").andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("COMPLETED"))
			.andExpect(jsonPath("$.externalReference").value("BKASH-RF-77120"))
			.andExpect(jsonPath("$.completedAt").isNotEmpty());

		assertThat(paymentStatus(orderId)).isEqualTo("REFUNDED");
		assertThat(orderStatus(orderId)).isEqualTo("CANCELLED");
		customerView(orderId).andExpect(jsonPath("$.paymentStatus").value("REFUNDED"))
			.andExpect(jsonPath("$.payment.refundStatus").value("COMPLETED"));
		assertThat(jdbc.queryForList("""
				SELECT action FROM audit_events WHERE entity_type = 'refund' AND entity_id = ?
				ORDER BY created_at, id""", String.class, refundId))
			.containsExactly("refund.approved", "refund.completed");
	}

	@Test
	void eachStepHappensOnceAndInOrder() throws Exception {
		UUID orderId = paidOrder();
		cancel(customer, orderId, null).andExpect(status().isOk());
		UUID refundId = onlyRefund(orderId);

		complete(refundId, "TOO-EARLY").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(RefundWorkflow.REFUND_NOT_APPROVED));
		decideRefund(refundId, "APPROVE", "Checked").andExpect(status().isOk());
		decideRefund(refundId, "REJECT", "Changed my mind").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(RefundWorkflow.REFUND_ALREADY_DECIDED));
		complete(refundId, "BANK-001").andExpect(status().isOk());
		complete(refundId, "BANK-002").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(RefundWorkflow.REFUND_NOT_APPROVED));

		assertThat(jdbc.queryForObject("SELECT external_reference FROM refunds WHERE id = ?", String.class, refundId))
			.isEqualTo("BANK-001");
		// Nothing left to refund.
		request(paymentOf(orderId), "Second refund").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(RefundWorkflow.PAYMENT_NOT_REFUNDABLE));
	}

	@Test
	void aRejectedRefundLeavesThePaymentAndAllowsANewRequest() throws Exception {
		UUID orderId = paidOrder();
		cancel(customer, orderId, null).andExpect(status().isOk());
		UUID refundId = onlyRefund(orderId);

		decideRefund(refundId, "REJECT", "Cake was already delivered").andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("REJECTED"));
		complete(refundId, "BANK-1").andExpect(status().isConflict());
		assertThat(paymentStatus(orderId)).isEqualTo("SUCCEEDED");

		request(paymentOf(orderId), "Reviewed again with the manager").andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("REQUESTED"))
			.andExpect(jsonPath("$.requestedByType").value("ADMIN"))
			.andExpect(jsonPath("$.requestedBy").value(userIdOf(admin).toString()))
			.andExpect(jsonPath("$.orderNumber").isString());
		assertThat(count("refunds")).isEqualTo(2);
	}

	@Test
	void anAdminCanRefundADeliveredOrderAfterAComplaint() throws Exception {
		UUID orderId = paidOrder();
		for (String step : List.of("PREPARING", "READY", "OUT_FOR_DELIVERY", "DELIVERED")) {
			patchStatus(orderId, step).andExpect(status().isOk());
		}
		UUID paymentId = paymentOf(orderId);

		String body = request(paymentId, "Box arrived crushed").andExpect(status().isCreated())
			.andExpect(jsonPath("$.paymentId").value(paymentId.toString()))
			.andExpect(jsonPath("$.orderId").value(orderId.toString()))
			.andExpect(jsonPath("$.requestReason").value("Box arrived crushed"))
			.andExpect(jsonPath("$.currency").value("BDT"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		UUID refundId = UUID.fromString(json.readTree(body).get("id").asString());
		request(paymentId, "Same complaint again").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(RefundWorkflow.REFUND_ALREADY_OPEN));

		// Requested by this ADMIN, so another one approves.
		decideRefund(reviewer, refundId, "APPROVE", "Photo confirms damage").andExpect(status().isOk());
		complete(refundId, "NAGAD-RF-1").andExpect(status().isOk());
		// The refund does not change fulfilment.
		assertThat(orderStatus(orderId)).isEqualTo("DELIVERED");
		assertThat(paymentStatus(orderId)).isEqualTo("REFUNDED");
		assertThat(jdbc.queryForObject("SELECT reason FROM audit_events WHERE action = 'refund.requested'",
				String.class)).isEqualTo("Box arrived crushed");
	}

	@Test
	void onlyReceivedPaymentsCanBeRefunded() throws Exception {
		UUID pendingManual = placeOrder(customer, "MANUAL", slot, line(fudge, 1, six));
		UUID pendingCod = placeOrder(customer, "COD", slot, line(cookies, 1));

		request(paymentOf(pendingManual), "Not paid yet").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(RefundWorkflow.PAYMENT_NOT_REFUNDABLE));
		request(paymentOf(pendingCod), "Cash not collected").andExpect(status().isConflict());
		request(UUID.randomUUID(), "Unknown payment").andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
		decideRefund(UUID.randomUUID(), "APPROVE", "Unknown").andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value(RefundWorkflow.REFUND_NOT_FOUND));
		complete(UUID.randomUUID(), "REF-1").andExpect(status().isNotFound());
		assertThat(count("refunds")).isZero();
	}

	@Test
	void notesReasonsAndReferencesAreRequired() throws Exception {
		UUID orderId = paidOrder();
		cancel(customer, orderId, null).andExpect(status().isOk());
		UUID refundId = onlyRefund(orderId);

		request(paymentOf(orderId), "no").andExpect(status().isUnprocessableContent());
		decideRefund(refundId, "APPROVE", "  ").andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		decideRefund(refundId, "MAYBE", "Not sure").andExpect(status().isBadRequest());
		decideRefund(refundId, "APPROVE", "Fine").andExpect(status().isOk());
		complete(refundId, "ab").andExpect(status().isUnprocessableContent());
		complete(refundId, "    ").andExpect(status().isUnprocessableContent());
		complete(refundId, "X".repeat(151)).andExpect(status().isUnprocessableContent());

		assertThat(jdbc.queryForObject("SELECT status::text FROM refunds WHERE id = ?", String.class, refundId))
			.isEqualTo("APPROVED");
	}

	@Test
	void onlyAdminsTouchRefunds() throws Exception {
		UUID orderId = paidOrder();
		cancel(customer, orderId, null).andExpect(status().isOk());
		UUID refundId = onlyRefund(orderId);

		mockMvc.perform(get("/api/v1/admin/refunds").header(HttpHeaders.AUTHORIZATION, customer))
			.andExpect(status().isForbidden());
		perform(customer, "/api/v1/admin/refunds/{id}/decision", refundId, "{\"decision\":\"APPROVE\",\"note\":\"Mine\"}")
			.andExpect(status().isForbidden());
		perform(customer, "/api/v1/admin/refunds/{id}/completion", refundId, "{\"externalReference\":\"SELF-1\"}")
			.andExpect(status().isForbidden());
		perform(customer, "/api/v1/admin/payments/{id}/refunds", paymentOf(orderId), "{\"reason\":\"Give it back\"}")
			.andExpect(status().isForbidden());
		mockMvc.perform(get("/api/v1/admin/refunds").param("status", "PAID").header(HttpHeaders.AUTHORIZATION, admin))
			.andExpect(status().isBadRequest());

		assertThat(jdbc.queryForObject("SELECT status::text FROM refunds WHERE id = ?", String.class, refundId))
			.isEqualTo("REQUESTED");
	}

	@Test
	void theListIsNewestFirstAndFiltersByStatus() throws Exception {
		UUID first = paidOrder();
		cancel(customer, first, null).andExpect(status().isOk());
		UUID second = paidOrder();
		cancel(customer, second, null).andExpect(status().isOk());
		decideRefund(onlyRefund(first), "REJECT", "Duplicate claim").andExpect(status().isOk());

		mockMvc.perform(get("/api/v1/admin/refunds").header(HttpHeaders.AUTHORIZATION, admin))
			.andExpect(jsonPath("$[*].orderId", contains(second.toString(), first.toString())));
		mockMvc.perform(get("/api/v1/admin/refunds").param("status", "REJECTED").header(HttpHeaders.AUTHORIZATION,
				admin)).andExpect(jsonPath("$[*].orderId", contains(first.toString())));
	}

	@Test
	void twoAdminsCompletingAtOnceRefundOnce() throws Exception {
		UUID orderId = paidOrder();
		cancel(customer, orderId, null).andExpect(status().isOk());
		UUID refundId = onlyRefund(orderId);
		UUID adminId = userIdOf(admin);
		workflow.decide(refundId, adminId, Decision.APPROVE, "Approved");

		List<String> outcomes = race(2, i -> () -> {
			try {
				return workflow.complete(refundId, adminId, "BANK-RACE-" + i).status();
			}
			catch (ApiException ex) {
				return ex.code();
			}
		});

		assertThat(outcomes).containsExactlyInAnyOrder("COMPLETED", RefundWorkflow.REFUND_NOT_APPROVED);
		assertThat(paymentStatus(orderId)).isEqualTo("REFUNDED");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'refund.completed'",
				Integer.class)).isEqualTo(1);
	}

	@Test
	void anAdminRequestRacingACancellationOpensOneRefund() throws Exception {
		UUID orderId = paidOrder();
		UUID paymentId = paymentOf(orderId);
		UUID adminId = userIdOf(admin);

		race(2, i -> () -> {
			if (i == 0) {
				return cancel(customer, orderId, null).andReturn().getResponse().getStatus();
			}
			try {
				workflow.request(paymentId, adminId, "Customer called to cancel", null);
				return 201;
			}
			catch (ApiException ex) {
				return ex.status().value();
			}
		});

		assertThat(jdbc.queryForObject("SELECT count(*) FROM refunds WHERE payment_id = ? AND status <> 'REJECTED'",
				Integer.class, paymentId)).isEqualTo(1);
	}

	// ------------------------------------------------------------------ second approver

	@Test
	void theRequesterCannotApproveTheirOwnRefund() throws Exception {
		UUID orderId = paidOrder();
		UUID refundId = requested(admin, paymentOf(orderId), "Goodwill for late delivery", "300.00");

		decideRefund(admin, refundId, "APPROVE", "Approving my own request").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(RefundWorkflow.SECOND_APPROVER_REQUIRED));
		assertThat(refundStatus(refundId)).isEqualTo("REQUESTED");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'refund.approved'",
				Integer.class)).isZero();

		// A distinct ADMIN approves; completion may then be recorded by either of them.
		decideRefund(reviewer, refundId, "APPROVE", "Checked the delivery log").andExpect(status().isOk())
			.andExpect(jsonPath("$.decidedBy").value(userIdOf(reviewer).toString()));
		complete(refundId, "BKASH-RF-300").andExpect(status().isOk());
		assertThat(jdbc.queryForMap("SELECT requested_by, decided_by FROM refunds WHERE id = ?", refundId))
			.containsEntry("requested_by", userIdOf(admin))
			.containsEntry("decided_by", userIdOf(reviewer));
	}

	@Test
	void theRequesterMayWithdrawTheirRequestButNotApproveIt() throws Exception {
		UUID orderId = paidOrder();
		UUID refundId = requested(admin, paymentOf(orderId), "Entered by mistake", null);

		decideRefund(admin, refundId, "REJECT", "Withdrawn: wrong order").andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("REJECTED"));
		assertThat(paymentStatus(orderId)).isEqualTo("SUCCEEDED");
	}

	@Test
	void aRefundOpenedByAnAdminCancellationNeedsAnotherAdmin() throws Exception {
		UUID orderId = paidOrder();
		mockMvc.perform(patch("/api/v1/admin/orders/{id}/status", orderId).header(HttpHeaders.AUTHORIZATION, admin)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"status\":\"CANCELLED\",\"reason\":\"Oven broke down\"}")).andExpect(status().isOk());
		UUID refundId = onlyRefund(orderId);

		decideRefund(admin, refundId, "APPROVE", "Approve").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(RefundWorkflow.SECOND_APPROVER_REQUIRED));
		decideRefund(reviewer, refundId, "APPROVE", "Approve").andExpect(status().isOk());
	}

	@Test
	void anAdminWhoCancelledTheirOwnOrderCannotApproveItsRefund() throws Exception {
		// The ADMIN shops as a customer: the request is theirs even though it is a customer request.
		UUID orderId = placeOrder(admin, "MANUAL", slot, line(fudge, 1, six));
		decide(reviewer, paymentOf(orderId), "APPROVE", "Matched statement").andExpect(status().isOk());
		cancel(admin, orderId, null).andExpect(status().isOk());
		UUID refundId = onlyRefund(orderId);
		assertThat(jdbc.queryForObject("SELECT requested_by_type::text FROM refunds WHERE id = ?", String.class,
				refundId)).isEqualTo("CUSTOMER");

		decideRefund(admin, refundId, "APPROVE", "My own order").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(RefundWorkflow.SECOND_APPROVER_REQUIRED));
		decideRefund(reviewer, refundId, "APPROVE", "Cancelled in time").andExpect(status().isOk());
	}

	@Test
	void theApproverMustBeAnEnabledAdminAtTheTimeOfTheDecision() throws Exception {
		UUID orderId = paidOrder();
		UUID refundId = requested(admin, paymentOf(orderId), "Box arrived crushed", null);
		UUID reviewerId = userIdOf(reviewer);

		// Customers are refused by role.
		decideRefund(customer, refundId, "APPROVE", "I approve").andExpect(status().isForbidden());
		// A demoted ADMIN still holding an unexpired ADMIN token is refused.
		jdbc.update("UPDATE users SET role = 'CUSTOMER' WHERE id = ?", reviewerId);
		decideRefund(reviewer, refundId, "APPROVE", "Approve").andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("FORBIDDEN"));
		// A disabled one is refused at the door (ADR-0028), and by the service as a second layer.
		jdbc.update("UPDATE users SET role = 'ADMIN', enabled = FALSE WHERE id = ?", reviewerId);
		decideRefund(reviewer, refundId, "APPROVE", "Approve").andExpect(status().isUnauthorized());
		assertThatThrownBy(() -> workflow.decide(refundId, reviewerId, Decision.APPROVE, "Approve"))
			.isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("FORBIDDEN"));
		assertThat(refundStatus(refundId)).isEqualTo("REQUESTED");

		jdbc.update("UPDATE users SET enabled = TRUE WHERE id = ?", reviewerId);
		// Re-enabled, the old token stays refused (ADR-0031); the reviewer signs in again.
		decideRefund(reviewer, refundId, "APPROVE", "Approve").andExpect(status().isUnauthorized());
		reviewer = bearerFor(reviewerId);
		decideRefund(reviewer, refundId, "APPROVE", "Approve").andExpect(status().isOk());
		jdbc.update("UPDATE users SET enabled = FALSE WHERE id = ?", userIdOf(admin));
		complete(refundId, "BANK-9").andExpect(status().isUnauthorized());
		assertThatThrownBy(() -> workflow.complete(refundId, userIdOf(admin), "BANK-9"))
			.isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("FORBIDDEN"));
		assertThat(refundStatus(refundId)).isEqualTo("APPROVED");
	}

	@Test
	void theDatabaseRefusesASelfApprovalThatBypassesTheService() throws Exception {
		UUID orderId = paidOrder();
		UUID refundId = requested(admin, paymentOf(orderId), "Bypass attempt", null);

		assertThatThrownBy(() -> jdbc.update("""
				UPDATE refunds SET status = 'APPROVED', decided_by = requested_by, decided_at = now(),
				                   decision_note = 'self' WHERE id = ?""", refundId))
			.hasMessageContaining("ck_refund_second_approver");
		assertThat(refundStatus(refundId)).isEqualTo("REQUESTED");
	}

	@Test
	void requesterAndReviewerApprovingAtOnceRecordOnlyTheReviewersApproval() throws Exception {
		UUID orderId = paidOrder();
		UUID refundId = requested(admin, paymentOf(orderId), "Goodwill", "100.00");
		UUID requesterId = userIdOf(admin);
		UUID reviewerId = userIdOf(reviewer);

		List<String> outcomes = race(2, i -> () -> {
			try {
				return workflow.decide(refundId, i == 0 ? requesterId : reviewerId, Decision.APPROVE, "Approve")
					.status();
			}
			catch (ApiException ex) {
				return ex.code();
			}
		});

		assertThat(outcomes.get(0)).isIn(RefundWorkflow.SECOND_APPROVER_REQUIRED,
				RefundWorkflow.REFUND_ALREADY_DECIDED);
		assertThat(outcomes.get(1)).isEqualTo("APPROVED");
		assertThat(jdbc.queryForObject("SELECT decided_by FROM refunds WHERE id = ?", UUID.class, refundId))
			.isEqualTo(reviewerId);
	}

	@Test
	void twoReviewersDecidingAtOnceRecordOneDecision() throws Exception {
		UUID orderId = paidOrder();
		cancel(customer, orderId, null).andExpect(status().isOk());
		UUID refundId = onlyRefund(orderId);
		UUID approverId = userIdOf(admin);
		UUID rejecterId = userIdOf(reviewer);

		List<String> outcomes = race(2, i -> () -> {
			try {
				return i == 0 ? workflow.decide(refundId, approverId, Decision.APPROVE, "Approve").status()
						: workflow.decide(refundId, rejecterId, Decision.REJECT, "Reject").status();
			}
			catch (ApiException ex) {
				return ex.code();
			}
		});

		assertThat(outcomes).containsOnlyOnce(RefundWorkflow.REFUND_ALREADY_DECIDED);
		String winner = outcomes.get(0).equals(RefundWorkflow.REFUND_ALREADY_DECIDED) ? outcomes.get(1)
				: outcomes.get(0);
		assertThat(winner).isIn("APPROVED", "REJECTED");
		assertThat(refundStatus(refundId)).isEqualTo(winner);
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM audit_events WHERE entity_type = 'refund' AND entity_id = ?
				AND action IN ('refund.approved', 'refund.rejected')""", Integer.class, refundId)).isEqualTo(1);
	}

	@Test
	void aRefundWithNoKnownRequesterStillNeedsAnEnabledAdminAndIsListedWithoutOne() throws Exception {
		UUID orderId = paidOrder();
		// No path in the service opens one; this stands for an older or system-opened request.
		UUID refundId = jdbc.queryForObject("""
				INSERT INTO refunds (payment_id, order_id, amount, request_reason, requested_by_type, requested_by)
				SELECT id, order_id, amount, 'Opened by the system', 'SYSTEM', NULL FROM payments WHERE id = ?
				RETURNING id""", UUID.class, paymentOf(orderId));
		String orderNumber = jdbc.queryForObject("SELECT order_number FROM orders WHERE id = ?", String.class,
				orderId);

		mockMvc.perform(get("/api/v1/admin/refunds").header(HttpHeaders.AUTHORIZATION, admin))
			.andExpect(jsonPath("$[0].id").value(refundId.toString()))
			.andExpect(jsonPath("$[0].requestedByType").value("SYSTEM"))
			.andExpect(jsonPath("$[0].requestedBy").value(nullValue()))
			.andExpect(jsonPath("$[0].orderNumber").value(orderNumber));

		decideRefund(customer, refundId, "APPROVE", "I approve").andExpect(status().isForbidden());
		jdbc.update("UPDATE users SET enabled = FALSE WHERE id = ?", userIdOf(reviewer));
		decideRefund(reviewer, refundId, "APPROVE", "Approve").andExpect(status().isUnauthorized());
		assertThat(refundStatus(refundId)).isEqualTo("REQUESTED");

		decideRefund(admin, refundId, "APPROVE", "Checked").andExpect(status().isOk());
		complete(refundId, "BANK-SYS-1").andExpect(status().isOk());
		assertThat(paymentStatus(orderId)).isEqualTo("REFUNDED");
	}

	@Test
	void theServerSetsTheCurrencyAndKeepsTheAmountItWasGiven() throws Exception {
		UUID orderId = paidOrder();

		perform(admin, "/api/v1/admin/payments/{id}/refunds", paymentOf(orderId),
				"{\"reason\":\"Goodwill\",\"amount\":100.00,\"currency\":\"USD\"}")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.currency").value("BDT"))
			.andExpect(jsonPath("$.amount").value(100.0));
		assertThat(jdbc.queryForMap("SELECT amount, currency FROM refunds WHERE order_id = ?", orderId))
			.containsEntry("amount", new BigDecimal("100.00"))
			.containsEntry("currency", "BDT");
	}

	// ------------------------------------------------------------------ partial refunds and the total cap

	@Test
	void partialRefundsAddUpToThePaymentAndOnlyThenRefundIt() throws Exception {
		UUID orderId = paidOrder();
		UUID paymentId = paymentOf(orderId);

		approveAndComplete(requested(admin, paymentId, "One box missing", "500.00"), "BKASH-RF-1");
		assertThat(paymentStatus(orderId)).isEqualTo("SUCCEEDED");
		adminOrder(orderId).andExpect(jsonPath("$.payments[0].refundedAmount").value(500.00))
			.andExpect(jsonPath("$.payments[0].status").value("SUCCEEDED"));

		// Without an amount, the request covers what is left.
		UUID rest = requested(admin, paymentId, "Order cancelled after all", null);
		assertThat(refundAmount(rest)).isEqualByComparingTo("810.00");
		approveAndComplete(rest, "BKASH-RF-2");

		assertThat(paymentStatus(orderId)).isEqualTo("REFUNDED");
		adminOrder(orderId).andExpect(jsonPath("$.payments[0].refundedAmount").value(1310.00));
		request(paymentId, "Anything else").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(RefundWorkflow.PAYMENT_NOT_REFUNDABLE));
	}

	@Test
	void aRequestCannotExceedWhatIsLeftOfThePayment() throws Exception {
		UUID orderId = paidOrder();
		UUID paymentId = paymentOf(orderId);

		request(paymentId, "Too much", "1310.01").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(RefundWorkflow.REFUND_EXCEEDS_REMAINING));
		approveAndComplete(requested(admin, paymentId, "Partial", "500.00"), "BANK-1");
		request(paymentId, "Over the rest", "810.01").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(RefundWorkflow.REFUND_EXCEEDS_REMAINING));
		request(paymentId, "Zero", "0").andExpect(status().isUnprocessableContent());
		request(paymentId, "Negative", "-5").andExpect(status().isUnprocessableContent());
		request(paymentId, "Fractions of poisha", "10.005").andExpect(status().isUnprocessableContent());

		request(paymentId, "Exactly the rest", "810.00").andExpect(status().isCreated());
		assertThat(count("refunds")).isEqualTo(2);
	}

	@Test
	void onlyOneRefundIsOpenAtATimeAndRejectedOnesDoNotCount() throws Exception {
		UUID orderId = paidOrder();
		UUID paymentId = paymentOf(orderId);
		UUID first = requested(admin, paymentId, "Full refund", null);

		request(paymentId, "Another one", "100.00").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(RefundWorkflow.REFUND_ALREADY_OPEN));
		decideRefund(reviewer, first, "APPROVE", "Approve").andExpect(status().isOk());
		// Approved but not yet sent is still open.
		request(paymentId, "Another one", "100.00").andExpect(status().isConflict());
		complete(first, "BANK-FULL").andExpect(status().isOk());
		request(paymentId, "After a full refund", "1.00").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(RefundWorkflow.PAYMENT_NOT_REFUNDABLE));

		UUID other = paidOrder();
		UUID rejected = requested(admin, paymentOf(other), "Full", null);
		decideRefund(reviewer, rejected, "REJECT", "Not eligible").andExpect(status().isOk());
		// The rejected 1310.00 does not use up the payment.
		request(paymentOf(other), "Full again", "1310.00").andExpect(status().isCreated());
	}

	@Test
	void cancellingAfterAPartialRefundRequestsOnlyTheRest() throws Exception {
		UUID orderId = paidOrder();
		approveAndComplete(requested(admin, paymentOf(orderId), "Goodwill", "300.00"), "BANK-GW");

		cancel(customer, orderId, null).andExpect(status().isOk());

		BigDecimal automatic = jdbc.queryForObject(
				"SELECT amount FROM refunds WHERE order_id = ? AND status = 'REQUESTED'", BigDecimal.class, orderId);
		assertThat(automatic).isEqualByComparingTo("1010.00");
	}

	@Test
	void theDatabaseRefusesRefundsBeyondThePayment() throws Exception {
		UUID orderId = paidOrder();
		UUID paymentId = paymentOf(orderId);
		UUID open = requested(admin, paymentId, "Partial", "1000.00");

		// Raising an open request past the payment, directly in SQL.
		assertThatThrownBy(() -> jdbc.update("UPDATE refunds SET amount = 1310.01 WHERE id = ?", open))
			.hasMessageContaining("ck_refund_within_payment");
		// Rejected rows never count, so this one is accepted ...
		UUID rejected = jdbc.queryForObject("""
				INSERT INTO refunds (payment_id, order_id, amount, request_reason, requested_by_type, status,
				                     decided_by, decided_at, decision_note)
				VALUES (?, ?, 1310.00, 'direct', 'SYSTEM', 'REJECTED', ?, now(), 'direct') RETURNING id""", UUID.class,
				paymentId, orderId, userIdOf(reviewer));
		// ... but reviving it would exceed the payment together with the open 1000.00.
		assertThatThrownBy(() -> jdbc.update("""
				UPDATE refunds SET status = 'COMPLETED', external_reference = 'X', completed_at = now()
				WHERE id = ?""", rejected)).hasMessageContaining("ck_refund_within_payment");
	}

	@Test
	void concurrentRequestsCannotTogetherExceedThePayment() throws Exception {
		UUID orderId = paidOrder();
		UUID paymentId = paymentOf(orderId);
		UUID first = userIdOf(admin);
		UUID second = userIdOf(reviewer);

		List<String> outcomes = race(2, i -> () -> {
			try {
				return workflow.request(paymentId, i == 0 ? first : second, "Refund " + i, Money.of("1000.00"))
					.status();
			}
			catch (ApiException ex) {
				return ex.code();
			}
		});

		assertThat(outcomes).containsExactlyInAnyOrder("REQUESTED", RefundWorkflow.REFUND_ALREADY_OPEN);
	}

	@Test
	void concurrentDirectInsertsAreCheckedOneAtATimeByTheDatabase() throws Exception {
		UUID orderId = paidOrder();
		UUID paymentId = paymentOf(orderId);
		UUID reviewerId = userIdOf(reviewer);

		// Bypassing the service: two 800.00 refunds of a 1310.00 payment, each in its own transaction.
		List<String> outcomes = race(2, i -> () -> {
			try {
				transactions.executeWithoutResult(status -> jdbc.update("""
						INSERT INTO refunds (payment_id, order_id, amount, request_reason, requested_by_type, status,
						                     decided_by, decided_at, decision_note, external_reference, completed_at)
						VALUES (?, ?, 800.00, 'direct', 'SYSTEM', 'COMPLETED', ?, now(), 'direct', ?, now())""",
						paymentId, orderId, reviewerId, "DIRECT-" + i));
				return "INSERTED";
			}
			catch (RuntimeException ex) {
				return String.valueOf(ex.getMessage()).contains("ck_refund_within_payment") ? "REFUSED"
						: String.valueOf(ex.getMessage());
			}
		});

		assertThat(outcomes).containsExactlyInAnyOrder("INSERTED", "REFUSED");
	}

	// ------------------------------------------------------------------ customer notice

	@Test
	void theCustomerIsToldWhenARefundHasBeenSent() throws Exception {
		UUID orderId = paidOrder();
		approveAndComplete(requested(admin, paymentOf(orderId), "One box missing", "500.00"), "BKASH-RF-77");
		sms.reset();
		jdbc.update("DELETE FROM outbox_events WHERE event_type <> 'refund.completed'");

		assertThat(dispatcher.dispatchDue()).isEqualTo(1);

		String number = jdbc.queryForObject("SELECT order_number FROM orders WHERE id = ?", String.class, orderId);
		String phone = jdbc.queryForObject("SELECT phone FROM users WHERE id = ?", String.class, userIdOf(customer));
		assertThat(sms.sent()).singleElement().satisfies(message -> {
			assertThat(message.to()).isEqualTo(phone);
			assertThat(message.text()).isEqualTo("Baked & Browned: your refund of BDT 500.00 for order " + number
					+ " has been sent. It may take a few days to reach your account.");
		});
		assertThat(jdbc.queryForMap("SELECT kind, order_id, status::text AS status FROM notifications"))
			.containsEntry("kind", "REFUND_COMPLETED")
			.containsEntry("order_id", orderId)
			.containsEntry("status", "SENT");
	}

	/** After every test, whatever it did: no payment has more refunded, or committed, than it received. */
	@AfterEach
	void refundsNeverExceedTheirPayment() {
		for (UUID paymentId : jdbc.queryForList("SELECT DISTINCT payment_id FROM refunds", UUID.class)) {
			Map<String, Object> totals = jdbc.queryForMap("""
					SELECT p.amount AS paid,
					       coalesce(sum(r.amount) FILTER (WHERE r.status = 'COMPLETED'), 0) AS refunded,
					       coalesce(sum(r.amount) FILTER (WHERE r.status <> 'REJECTED'), 0) AS committed
					FROM payments p LEFT JOIN refunds r ON r.payment_id = p.id
					WHERE p.id = ? GROUP BY p.amount""", paymentId);
			BigDecimal paid = (BigDecimal) totals.get("paid");
			assertThat((BigDecimal) totals.get("refunded")).isLessThanOrEqualTo(paid);
			assertThat((BigDecimal) totals.get("committed")).isLessThanOrEqualTo(paid);
		}
	}

	// ------------------------------------------------------------------ helpers

	/** Opens a refund request through the API and returns its id. */
	private UUID requested(String bearer, UUID paymentId, String reason, @Nullable String amount) throws Exception {
		String body = perform(bearer, "/api/v1/admin/payments/{id}/refunds", paymentId, refundBody(reason, amount))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return UUID.fromString(json.readTree(body).get("id").asString());
	}

	private ResultActions request(UUID paymentId, String reason, String amount) throws Exception {
		return perform(admin, "/api/v1/admin/payments/{id}/refunds", paymentId, refundBody(reason, amount));
	}

	private static String refundBody(String reason, @Nullable String amount) {
		return amount == null ? "{\"reason\":\"" + reason + "\"}"
				: "{\"reason\":\"" + reason + "\",\"amount\":" + amount + "}";
	}

	/** The second ADMIN approves, the first records the transfer. */
	private void approveAndComplete(UUID refundId, String reference) throws Exception {
		decideRefund(reviewer, refundId, "APPROVE", "Reviewed").andExpect(status().isOk());
		complete(refundId, reference).andExpect(status().isOk());
	}

	private String refundStatus(UUID refundId) {
		return jdbc.queryForObject("SELECT status::text FROM refunds WHERE id = ?", String.class, refundId);
	}

	private BigDecimal refundAmount(UUID refundId) {
		return jdbc.queryForObject("SELECT amount FROM refunds WHERE id = ?", BigDecimal.class, refundId);
	}

	private ResultActions adminOrder(UUID orderId) throws Exception {
		return mockMvc.perform(get("/api/v1/admin/orders/{id}", orderId).header(HttpHeaders.AUTHORIZATION, admin));
	}

	/** A MANUAL order whose payment an ADMIN verified: CONFIRMED with a SUCCEEDED payment of 1310.00. */
	private UUID paidOrder() throws Exception {
		UUID orderId = placeOrder(customer, "MANUAL", slot, line(fudge, 1, six));
		decide(admin, paymentOf(orderId), "APPROVE", "Matched statement").andExpect(status().isOk());
		return orderId;
	}

	private UUID onlyRefund(UUID orderId) {
		return jdbc.queryForObject("SELECT id FROM refunds WHERE order_id = ?", UUID.class, orderId);
	}

	private ResultActions request(UUID paymentId, String reason) throws Exception {
		return perform(admin, "/api/v1/admin/payments/{id}/refunds", paymentId, "{\"reason\":\"" + reason + "\"}");
	}

	private ResultActions decideRefund(UUID refundId, String decision, String note) throws Exception {
		return decideRefund(admin, refundId, decision, note);
	}

	private ResultActions decideRefund(String bearer, UUID refundId, String decision, String note) throws Exception {
		return perform(bearer, "/api/v1/admin/refunds/{id}/decision", refundId,
				"{\"decision\":\"" + decision + "\",\"note\":\"" + note + "\"}");
	}

	private ResultActions complete(UUID refundId, String reference) throws Exception {
		return perform(admin, "/api/v1/admin/refunds/{id}/completion", refundId,
				"{\"externalReference\":\"" + reference + "\"}");
	}

	private ResultActions perform(String bearer, String path, UUID id, String body) throws Exception {
		return mockMvc.perform(post(path, id).header(HttpHeaders.AUTHORIZATION, bearer)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private ResultActions patchStatus(UUID orderId, String status) throws Exception {
		return mockMvc.perform(patch("/api/v1/admin/orders/{id}/status", orderId).header(HttpHeaders.AUTHORIZATION, admin)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"status\":\"" + status + "\"}"));
	}

	private ResultActions customerView(UUID orderId) throws Exception {
		return mockMvc.perform(get("/api/v1/orders/{id}", orderId).header(HttpHeaders.AUTHORIZATION, customer));
	}

}
