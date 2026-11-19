package com.bakedandbrowned.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

import com.bakedandbrowned.common.web.ApiException;
import com.bakedandbrowned.ordering.service.HoldExpiryJob;
import com.bakedandbrowned.payment.dto.PaymentDtos.PaymentVerificationRequest.Decision;
import com.bakedandbrowned.payment.service.PaymentVerification;

/**
 * ADMIN payment decisions (ADR-0011), the verification queue and proof downloads: approve and
 * reject paths, late payments after expiry, COD collection, authorization, and races.
 */
class PaymentVerificationIntegrationTests extends ManualPaymentIntegrationTest {

	@Autowired
	private HoldExpiryJob expiry;

	@Autowired
	private PaymentVerification verification;

	// ------------------------------------------------------------------ MANUAL

	@Test
	void approvingAManualPaymentConfirmsTheOrderAndItsHolds() throws Exception {
		String customer = bearer("CUSTOMER");
		String admin = bearer("ADMIN");
		UUID orderId = placeOrder(customer, "MANUAL", slot, line(fudge, 1, six));
		submitted(customer, orderId, "TRX-OK-1");
		UUID paymentId = paymentOf(orderId);

		decide(admin, paymentId, "APPROVE", "Matched bKash statement line 14").andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("SUCCEEDED"))
			.andExpect(jsonPath("$.method").value("MANUAL"))
			.andExpect(jsonPath("$.verifiedBy").value(userIdOf(admin).toString()))
			.andExpect(jsonPath("$.verificationNote").value("Matched bKash statement line 14"))
			.andExpect(jsonPath("$.latestSubmission.status").value("VERIFIED"))
			.andExpect(jsonPath("$.latestSubmission.transactionReference").value("TRX-OK-1"));

		assertThat(orderStatus(orderId)).isEqualTo("CONFIRMED");
		assertThat(slotReservationStatus(orderId)).isEqualTo("CONFIRMED");
		assertThat(stockReservationStatuses(orderId)).containsExactly("CONFIRMED");
		assertThat(reservedUnits(slot)).isEqualTo(2);
		assertThat(reservedStock(chocolate)).isEqualByComparingTo("100");
		assertThat(events(orderId)).containsExactly("order.placed", "order.confirmed");
		assertThat(jdbc.queryForMap("""
				SELECT from_status::text AS from_status, actor_type::text AS actor, actor_user_id
				FROM order_status_history WHERE order_id = ? AND to_status = 'CONFIRMED'""", orderId))
			.containsEntry("from_status", "PENDING_PAYMENT")
			.containsEntry("actor", "ADMIN")
			.containsEntry("actor_user_id", userIdOf(admin));
		assertThat(audit(paymentId)).containsEntry("action", "payment.manual_approved")
			.containsEntry("actor_user_id", userIdOf(admin))
			.containsEntry("reason", "Matched bKash statement line 14");
		mockMvc.perform(get("/api/v1/orders/{id}", orderId).header(HttpHeaders.AUTHORIZATION, customer))
			.andExpect(jsonPath("$.status").value("CONFIRMED"))
			.andExpect(jsonPath("$.paymentStatus").value("SUCCEEDED"))
			.andExpect(jsonPath("$.payment.manualSubmissionStatus").value("VERIFIED"));

		// Decided once; the confirmed order is out of the expiry job's reach.
		decide(admin, paymentId, "REJECT", "Second look").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(PaymentVerification.PAYMENT_ALREADY_DECIDED));
		overdue(orderId);
		assertThat(expiry.expireDue()).isZero();
	}

	@Test
	void rejectingAManualPaymentCancelsTheOrderAndReleasesItsHolds() throws Exception {
		String customer = bearer("CUSTOMER");
		String admin = bearer("ADMIN");
		UUID orderId = placeOrder(customer, "MANUAL", slot, line(fudge, 1, six));
		submitted(customer, orderId, "TRX-FAKE-1");
		UUID paymentId = paymentOf(orderId);

		decide(admin, paymentId, "REJECT", "No such transaction on the statement").andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("FAILED"))
			.andExpect(jsonPath("$.failureReason").value("Manual payment rejected after verification."))
			.andExpect(jsonPath("$.latestSubmission.status").value("REJECTED"));

		assertThat(orderStatus(orderId)).isEqualTo("CANCELLED");
		assertThat(slotReservationStatus(orderId)).isEqualTo("RELEASED");
		assertThat(stockReservationStatuses(orderId)).containsExactly("RELEASED");
		assertThat(reservedUnits(slot)).isZero();
		assertThat(reservedStock(chocolate)).isEqualByComparingTo("0");
		assertThat(events(orderId)).containsExactly("order.placed", "order.cancelled");
		assertThat(jdbc.queryForMap("""
				SELECT actor_type::text AS actor, reason FROM order_status_history
				WHERE order_id = ? AND to_status = 'CANCELLED'""", orderId))
			.containsEntry("actor", "ADMIN")
			.containsEntry("reason", "No such transaction on the statement");
		assertThat(audit(paymentId)).containsEntry("action", "payment.manual_rejected");

		// A rejected reference may be used again (for example after a typo by the customer).
		UUID retry = placeOrder(customer, "MANUAL", slot, line(fudge, 1, six));
		submitted(customer, retry, "TRX-FAKE-1");
	}

	@Test
	void aManualPaymentVerifiedAfterExpiryOpensAReconciliationCase() throws Exception {
		String customer = bearer("CUSTOMER");
		String admin = bearer("ADMIN");
		UUID orderId = placeOrder(customer, "MANUAL", slot, line(fudge, 1, six));
		overdue(orderId);
		assertThat(expiry.expireDue()).isEqualTo(1);
		UUID paymentId = paymentOf(orderId);

		decide(admin, paymentId, "APPROVE", "Transfer arrived 40 minutes late").andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("SUCCEEDED"))
			.andExpect(jsonPath("$.latestSubmission").isEmpty());

		// Never reinstated automatically: the holds stay released and an ADMIN resolves the case.
		assertThat(orderStatus(orderId)).isEqualTo("EXPIRED");
		assertThat(slotReservationStatus(orderId)).isEqualTo("RELEASED");
		assertThat(reservedUnits(slot)).isZero();
		Map<String, Object> reconciliation = jdbc.queryForMap("""
				SELECT case_type::text AS case_type, status::text AS status, details->>'orderStatus' AS order_status
				FROM payment_reconciliation_cases WHERE payment_id = ?""", paymentId);
		assertThat(reconciliation).containsEntry("case_type", "LATE_SUCCESS")
			.containsEntry("status", "OPEN")
			.containsEntry("order_status", "EXPIRED");
		assertThat(audit(paymentId)).containsEntry("action", "payment.manual_approved_late");

		decide(admin, paymentId, "APPROVE", "Again").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(PaymentVerification.PAYMENT_ALREADY_DECIDED));
	}

	@Test
	void anOpenSubmissionOnAnExpiredOrderCanStillBeRejected() throws Exception {
		String customer = bearer("CUSTOMER");
		String admin = bearer("ADMIN");
		UUID orderId = placeOrder(customer, "MANUAL", slot, line(fudge, 1, six));
		submitted(customer, orderId, "TRX-SLOW-1");
		// The review window ended without a decision, so the job expired the order.
		jdbc.update("""
				UPDATE manual_payment_submissions SET created_at = now() - interval '7 hours'
				WHERE payment_id = ?""", paymentOf(orderId));
		jdbc.update("UPDATE orders SET expires_at = now() - interval '6 hours 50 minutes' WHERE id = ?", orderId);
		assertThat(expiry.expireDue()).isEqualTo(1);
		UUID paymentId = paymentOf(orderId);

		decide(admin, paymentId, "REJECT", "Reference belongs to another customer").andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CANCELLED"))
			.andExpect(jsonPath("$.latestSubmission.status").value("REJECTED"))
			.andExpect(jsonPath("$.verificationNote").value("Reference belongs to another customer"));
		assertThat(orderStatus(orderId)).isEqualTo("EXPIRED");
		assertThat(count("payment_reconciliation_cases")).isZero();
		assertThat(audit(paymentId)).containsEntry("action", "payment.manual_rejected_late");

		// Nothing left to decide.
		decide(admin, paymentId, "REJECT", "Again").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(PaymentVerification.PAYMENT_ALREADY_DECIDED));
	}

	// ------------------------------------------------------------------ COD and ONLINE

	@Test
	void codCollectionIsRecordedButCannotBeRejected() throws Exception {
		String customer = bearer("CUSTOMER");
		String admin = bearer("ADMIN");
		UUID orderId = placeOrder(customer, "COD", slot, line(fudge, 1, six));
		UUID paymentId = paymentOf(orderId);

		decide(admin, paymentId, "REJECT", "Customer refused").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(PaymentVerification.VERIFICATION_NOT_ALLOWED));
		decide(admin, paymentId, "APPROVE", "Cash 1310 received by rider").andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("SUCCEEDED"))
			.andExpect(jsonPath("$.method").value("COD"));

		assertThat(orderStatus(orderId)).isEqualTo("CONFIRMED");
		assertThat(events(orderId)).containsExactly("order.placed");
		assertThat(audit(paymentId)).containsEntry("action", "payment.cod_collected");
	}

	@Test
	void cashCannotBeCollectedForACancelledOrder() throws Exception {
		String customer = bearer("CUSTOMER");
		UUID orderId = placeOrder(customer, "COD", slot, line(fudge, 1, six));
		cancel(customer, orderId, null).andExpect(status().isOk());

		// The cancellation already closed the payment.
		decide(bearer("ADMIN"), paymentOf(orderId), "APPROVE", "Cash received").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(PaymentVerification.PAYMENT_ALREADY_DECIDED));
	}

	@Test
	void onlinePaymentsAreNeverVerifiedByHand() throws Exception {
		UUID orderId = placeOrder(bearer("CUSTOMER"), "ONLINE", slot, line(fudge, 1, six));

		decide(bearer("ADMIN"), paymentOf(orderId), "APPROVE", "Looks paid").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(PaymentVerification.VERIFICATION_NOT_ALLOWED));
		assertThat(paymentStatus(orderId)).isEqualTo("PENDING");
		assertThat(orderStatus(orderId)).isEqualTo("PENDING_PAYMENT");
	}

	// ------------------------------------------------------------------ authorization and input

	@Test
	void onlyAnAdminDecidesAndANoteIsRequired() throws Exception {
		String customer = bearer("CUSTOMER");
		String admin = bearer("ADMIN");
		UUID orderId = placeOrder(customer, "MANUAL", slot, line(fudge, 1, six));
		submitted(customer, orderId, "TRX-SELF-1");
		UUID paymentId = paymentOf(orderId);

		decide(customer, paymentId, "APPROVE", "I paid, honestly").andExpect(status().isForbidden());
		decide(admin, paymentId, "APPROVE", "  ").andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		decide(admin, paymentId, "APPROVE", "ok").andExpect(status().isUnprocessableContent());
		decide(admin, paymentId, "MAYBE", "Not sure").andExpect(status().isBadRequest());
		decide(admin, UUID.randomUUID(), "APPROVE", "Checked").andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));

		assertThat(paymentStatus(orderId)).isEqualTo("PENDING");
		assertThat(submissionStatus(orderId)).isEqualTo("SUBMITTED");
		assertThat(count("audit_events")).isZero();
	}

	// ------------------------------------------------------------------ races

	@RepeatedTest(5)
	void approvalRacingExpiryNeverConfirmsReleasedHolds() throws Exception {
		String customer = bearer("CUSTOMER");
		UUID admin = userIdOf(bearer("ADMIN"));
		UUID orderId = placeOrder(customer, "MANUAL", slot, line(fudge, 1, six));
		overdue(orderId);
		UUID paymentId = paymentOf(orderId);

		race(2, i -> () -> i == 0 ? expiry.expireDue()
				: verification.verify(paymentId, admin, Decision.APPROVE, "Statement checked").hashCode());

		assertThat(paymentStatus(orderId)).isEqualTo("SUCCEEDED");
		if ("CONFIRMED".equals(orderStatus(orderId))) {
			// Approval won: the holds were confirmed and expiry skipped the order.
			assertThat(slotReservationStatus(orderId)).isEqualTo("CONFIRMED");
			assertThat(reservedUnits(slot)).isEqualTo(2);
			assertThat(count("payment_reconciliation_cases")).isZero();
		}
		else {
			// Expiry won: the late approval opened a case instead of confirming.
			assertThat(orderStatus(orderId)).isEqualTo("EXPIRED");
			assertThat(slotReservationStatus(orderId)).isEqualTo("RELEASED");
			assertThat(reservedUnits(slot)).isZero();
			assertThat(count("payment_reconciliation_cases")).isEqualTo(1);
		}
	}

	@Test
	void twoAdminsDecidingAtOnceRecordOneDecision() throws Exception {
		String customer = bearer("CUSTOMER");
		UUID first = userIdOf(bearer("ADMIN"));
		UUID second = userIdOf(bearer("ADMIN"));
		UUID orderId = placeOrder(customer, "MANUAL", slot, line(fudge, 1, six));
		submitted(customer, orderId, "TRX-RACE-1");
		UUID paymentId = paymentOf(orderId);

		List<String> outcomes = race(2, i -> () -> {
			try {
				return verification
					.verify(paymentId, i == 0 ? first : second, i == 0 ? Decision.APPROVE : Decision.REJECT, "Checked")
					.status();
			}
			catch (ApiException ex) {
				return ex.code();
			}
		});

		assertThat(outcomes).contains(PaymentVerification.PAYMENT_ALREADY_DECIDED);
		assertThat(outcomes).containsAnyOf("SUCCEEDED", "FAILED");
		assertThat(count("audit_events")).isEqualTo(1);
		assertThat(orderStatus(orderId)).isIn("CONFIRMED", "CANCELLED");
	}

	// ------------------------------------------------------------------ queue and proof

	@Test
	void theQueueListsOpenManualSubmissionsOldestFirst() throws Exception {
		String customer = bearer("CUSTOMER");
		String admin = bearer("ADMIN");
		UUID older = placeOrder(customer, "MANUAL", slot, line(fudge, 1, six));
		UUID newer = placeOrder(customer, "MANUAL", slot, line(fudge, 1, six));
		UUID notSubmitted = placeOrder(customer, "MANUAL", slot, line(cookies, 1));
		placeOrder(customer, "COD", slot, line(cookies, 1));
		submitted(customer, newer, "TRX-Q-2");
		submitted(customer, older, "TRX-Q-1");
		jdbc.update("""
				UPDATE manual_payment_submissions SET created_at = now() - interval '10 minutes'
				WHERE payment_id = ?""", paymentOf(older));

		mockMvc.perform(get("/api/v1/admin/payments").param("awaitingVerification", "true")
			.header(HttpHeaders.AUTHORIZATION, admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(2))
			.andExpect(jsonPath("$.content[*].orderId", contains(older.toString(), newer.toString())))
			.andExpect(jsonPath("$.content[0].latestSubmission.transactionReference").value("TRX-Q-1"))
			.andExpect(jsonPath("$.content[0].latestSubmission.hasProof").value(false))
			.andExpect(jsonPath("$.content[0].amount").value(1310.00));

		mockMvc.perform(get("/api/v1/admin/payments").param("method", "MANUAL")
			.param("size", "2")
			.header(HttpHeaders.AUTHORIZATION, admin))
			.andExpect(jsonPath("$.totalElements").value(3))
			.andExpect(jsonPath("$.content.length()").value(2))
			.andExpect(jsonPath("$.content[0].orderId").value(notSubmitted.toString()));
		mockMvc.perform(get("/api/v1/admin/payments").param("status", "PENDING")
			.header(HttpHeaders.AUTHORIZATION, admin)).andExpect(jsonPath("$.totalElements").value(4));
		mockMvc.perform(get("/api/v1/admin/payments").param("status", "BOGUS")
			.header(HttpHeaders.AUTHORIZATION, admin)).andExpect(status().is4xxClientError());
		mockMvc.perform(get("/api/v1/admin/payments").header(HttpHeaders.AUTHORIZATION, customer))
			.andExpect(status().isForbidden());
	}

	@Test
	void theProofIsDownloadedByAnAdminAsAnAttachment() throws Exception {
		String customer = bearer("CUSTOMER");
		String admin = bearer("ADMIN");
		UUID orderId = placeOrder(customer, "MANUAL", slot, line(fudge, 1, six));
		submit(customer, orderId, "BKASH", "TRX-PROOF-1", proof("../../etc/passwd.png", "text/html", PNG))
			.andExpect(status().isCreated());
		UUID paymentId = paymentOf(orderId);
		String orderNumber = jdbc.queryForObject("SELECT order_number FROM orders WHERE id = ?", String.class,
				orderId);

		mockMvc.perform(get("/api/v1/admin/payments/{id}/proof", paymentId).header(HttpHeaders.AUTHORIZATION, admin))
			.andExpect(status().isOk())
			.andExpect(content().contentType("image/png"))
			.andExpect(content().bytes(PNG))
			.andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
					"attachment; filename=\"payment-proof-" + orderNumber + ".png\""))
			.andExpect(header().string("X-Content-Type-Options", "nosniff"))
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
		// The web app accepts every proof type in one request (it cannot know the file's type).
		mockMvc.perform(get("/api/v1/admin/payments/{id}/proof", paymentId).header(HttpHeaders.AUTHORIZATION, admin)
			.header(HttpHeaders.ACCEPT, "image/jpeg, image/png, application/pdf"))
			.andExpect(status().isOk())
			.andExpect(content().contentType("image/png"));
		// The file's own type is returned whatever was asked for.
		mockMvc.perform(get("/api/v1/admin/payments/{id}/proof", paymentId).header(HttpHeaders.AUTHORIZATION, admin)
			.header(HttpHeaders.ACCEPT, "image/jpeg"))
			.andExpect(status().isOk())
			.andExpect(content().contentType("image/png"));

		mockMvc.perform(get("/api/v1/admin/payments/{id}/proof", paymentId).header(HttpHeaders.AUTHORIZATION, customer))
			.andExpect(status().isForbidden());
		UUID withoutProof = placeOrder(customer, "MANUAL", slot, line(cookies, 1));
		submitted(customer, withoutProof, "TRX-PROOF-2");
		mockMvc.perform(get("/api/v1/admin/payments/{id}/proof", paymentOf(withoutProof))
			.header(HttpHeaders.AUTHORIZATION, admin))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PROOF_NOT_FOUND"));
		mockMvc.perform(get("/api/v1/admin/payments/{id}/proof", UUID.randomUUID())
			.header(HttpHeaders.AUTHORIZATION, admin)).andExpect(status().isNotFound());
	}

	// ------------------------------------------------------------------ helpers

	private Map<String, Object> audit(UUID paymentId) {
		return jdbc.queryForMap("""
				SELECT action, actor_user_id, reason, entity_type FROM audit_events
				WHERE entity_id = ? ORDER BY created_at DESC LIMIT 1""", paymentId);
	}

}
