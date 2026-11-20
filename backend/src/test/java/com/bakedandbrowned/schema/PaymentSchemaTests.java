package com.bakedandbrowned.schema;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Payments, manual verification, refunds and reconciliation (ADR-0008, ADR-0011). */
class PaymentSchemaTests extends AbstractSchemaTest {

	@Test
	void atMostOneSucceededPaymentPerOrder() {
		UUID orderId = order(user(), "ONLINE", "CONFIRMED");
		UUID first = payment(orderId, "ONLINE", "SUCCEEDED");
		jdbc.update("UPDATE payments SET superseded_at = now() WHERE id = ?", first);

		assertRejectedBy("uq_payment_success", () -> payment(orderId, "ONLINE", "SUCCEEDED"));
	}

	@Test
	void exactlyOneCurrentPaymentPerOrder() {
		UUID orderId = order(user(), "ONLINE", "PENDING_PAYMENT");
		UUID failed = payment(orderId, "ONLINE", "FAILED");

		assertRejectedBy("uq_payment_current", () -> payment(orderId, "ONLINE", "PENDING"));
		jdbc.update("UPDATE payments SET superseded_at = now() WHERE id = ?", failed);
		payment(orderId, "ONLINE", "PENDING");
	}

	@Test
	void verificationRecordIsAllOrNothing() {
		UUID adminId = admin();
		UUID paymentId = payment(order(user(), "MANUAL", "PENDING_PAYMENT"), "MANUAL", "PENDING");

		assertRejectedBy("ck_payment_verification_complete", () -> jdbc.update(
				"UPDATE payments SET verified_by = ?, verified_at = now() WHERE id = ?", adminId, paymentId));
		jdbc.update("""
				UPDATE payments SET status = 'SUCCEEDED', verified_by = ?, verified_at = now(),
				                    verification_note = 'Matched bKash statement line' WHERE id = ?""",
				adminId, paymentId);
	}

	@Test
	void transactionReferenceCannotBackTwoPaymentsUnlessRejected() {
		UUID customer = user();
		UUID firstPayment = payment(order(customer, "MANUAL", "PENDING_PAYMENT"), "MANUAL", "PENDING");
		UUID secondPayment = payment(order(customer, "MANUAL", "PENDING_PAYMENT"), "MANUAL", "PENDING");
		String submit = """
				INSERT INTO manual_payment_submissions (payment_id, submitted_by, provider, transaction_reference)
				VALUES (?, ?, 'BKASH', 'TRX9A8B7C') RETURNING id""";

		UUID firstSubmission = jdbc.queryForObject(submit, UUID.class, firstPayment, customer);
		assertRejectedBy("uq_manual_reference", () -> jdbc.queryForObject(submit, UUID.class, secondPayment, customer));

		jdbc.update("UPDATE manual_payment_submissions SET status = 'REJECTED' WHERE id = ?", firstSubmission);
		jdbc.queryForObject(submit, UUID.class, secondPayment, customer);
	}

	@Test
	void onlyOneOpenSubmissionPerPayment() {
		UUID customer = user();
		UUID paymentId = payment(order(customer, "MANUAL", "PENDING_PAYMENT"), "MANUAL", "PENDING");
		String submit = """
				INSERT INTO manual_payment_submissions (payment_id, submitted_by, provider, transaction_reference)
				VALUES (?, ?, 'NAGAD', ?)""";

		jdbc.update(submit, paymentId, customer, "NG-0001");
		assertRejectedBy("uq_manual_submission_open", () -> jdbc.update(submit, paymentId, customer, "NG-0002"));
	}

	@Test
	void proofFileMustBeAllowedTypeAndAtMostFiveMegabytes() {
		UUID customer = user();
		UUID paymentId = payment(order(customer, "MANUAL", "PENDING_PAYMENT"), "MANUAL", "PENDING");
		String submit = """
				INSERT INTO manual_payment_submissions (payment_id, submitted_by, provider, transaction_reference,
				    proof_storage_key, proof_content_type, proof_size_bytes, proof_sha256)
				VALUES (?, ?, 'BANK_TRANSFER', ?, 'proofs/x', ?, ?, repeat('e', 64))""";

		assertRejectedWith(CHECK_VIOLATION, "manual_payment_submissions",
				() -> jdbc.update(submit, paymentId, customer, "REF-HTML", "text/html", 1000));
		assertRejectedWith(CHECK_VIOLATION, "manual_payment_submissions",
				() -> jdbc.update(submit, paymentId, customer, "REF-BIG", "image/png", 5_242_881));
		jdbc.update(submit, paymentId, customer, "REF-OK", "application/pdf", 5_242_880);
	}

	@Test
	void refundWorkflowRequiresDecisionAndExternalReference() {
		UUID adminId = admin();
		UUID orderId = order(user(), "MANUAL", "CANCELLED");
		UUID paymentId = payment(orderId, "MANUAL", "SUCCEEDED");
		UUID refund = jdbc.queryForObject("""
				INSERT INTO refunds (payment_id, order_id, amount, request_reason, requested_by_type)
				VALUES (?, ?, 1100.00, 'Cancelled before cutoff', 'SYSTEM') RETURNING id""",
				UUID.class, paymentId, orderId);

		assertRejectedWith(CHECK_VIOLATION, "refunds",
				() -> jdbc.update("UPDATE refunds SET status = 'APPROVED' WHERE id = ?", refund));
		jdbc.update("""
				UPDATE refunds SET status = 'APPROVED', decided_by = ?, decided_at = now(),
				                   decision_note = 'Approved per policy' WHERE id = ?""", adminId, refund);
		assertRejectedWith(CHECK_VIOLATION, "refunds",
				() -> jdbc.update("UPDATE refunds SET status = 'COMPLETED' WHERE id = ?", refund));
		jdbc.update("""
				UPDATE refunds SET status = 'COMPLETED', external_reference = 'BANK-RF-778',
				                   completed_at = now() WHERE id = ?""", refund);
	}

	@Test
	void onlyOneOpenRefundPerPaymentAndNeverMoreThanWasPaid() {
		// V7 (ADR-0017): partial refunds; the payment of these test orders is 1100.00.
		UUID adminId = admin();
		UUID orderId = order(user(), "ONLINE", "CANCELLED");
		UUID paymentId = payment(orderId, "ONLINE", "SUCCEEDED");
		String request = """
				INSERT INTO refunds (payment_id, order_id, amount, request_reason, requested_by_type)
				VALUES (?, ?, 400.00, 'Partial refund', 'SYSTEM') RETURNING id""";

		UUID first = jdbc.queryForObject(request, UUID.class, paymentId, orderId);
		assertRejectedBy("uq_refund_open_per_payment", () -> jdbc.queryForObject(request, UUID.class, paymentId, orderId));

		// Once the first is completed, another may open: 400 + 400 is within 1100.
		jdbc.update("""
				UPDATE refunds SET status = 'COMPLETED', decided_by = ?, decided_at = now(), decision_note = 'Approved',
				                   external_reference = 'BANK-1', completed_at = now() WHERE id = ?""", adminId, first);
		UUID second = jdbc.queryForObject(request, UUID.class, paymentId, orderId);
		jdbc.update("""
				UPDATE refunds SET status = 'COMPLETED', decided_by = ?, decided_at = now(), decision_note = 'Approved',
				                   external_reference = 'BANK-2', completed_at = now() WHERE id = ?""", adminId, second);
		// A third 400 would make 1200 of 1100.
		assertRejectedBy("ck_refund_within_payment", () -> jdbc.queryForObject(request, UUID.class, paymentId, orderId));
		jdbc.queryForObject(request.replace("400.00", "300.00"), UUID.class, paymentId, orderId);
	}

	@Test
	void whoeverRequestedARefundNeverApprovesIt() {
		UUID requester = admin();
		UUID reviewer = admin();
		UUID orderId = order(user(), "ONLINE", "CANCELLED");
		UUID paymentId = payment(orderId, "ONLINE", "SUCCEEDED");
		UUID refundId = jdbc.queryForObject("""
				INSERT INTO refunds (payment_id, order_id, amount, request_reason, requested_by_type, requested_by)
				VALUES (?, ?, 1100.00, 'Goodwill', 'ADMIN', ?) RETURNING id""", UUID.class, paymentId, orderId, requester);
		String approve = """
				UPDATE refunds SET status = 'APPROVED', decided_by = ?, decided_at = now(), decision_note = 'Approved'
				WHERE id = ?""";

		assertRejectedBy("ck_refund_second_approver", () -> jdbc.update(approve, requester, refundId));
		jdbc.update(approve, reviewer, refundId);
	}

	@Test
	void aRefundRequestedByAPersonNamesThem() {
		// V15: without the requester, the second-approver check above could not apply.
		UUID orderId = order(user(), "ONLINE", "CANCELLED");
		UUID paymentId = payment(orderId, "ONLINE", "SUCCEEDED");
		String request = """
				INSERT INTO refunds (payment_id, order_id, amount, request_reason, requested_by_type, requested_by)
				VALUES (?, ?, 100.00, 'Goodwill', ?::actor_type, ?)""";

		assertRejectedBy("ck_refund_requester_known", () -> jdbc.update(request, paymentId, orderId, "ADMIN", null));
		assertRejectedBy("ck_refund_requester_known",
				() -> jdbc.update(request, paymentId, orderId, "CUSTOMER", null));
		// Nor can an update remove it later.
		jdbc.update(request, paymentId, orderId, "ADMIN", admin());
		assertRejectedBy("ck_refund_requester_known",
				() -> jdbc.update("UPDATE refunds SET requested_by = NULL WHERE payment_id = ?", paymentId));
		// A request opened by the system names no one.
		jdbc.update("UPDATE refunds SET status = 'REJECTED', decided_by = ?, decided_at = now(), decision_note = 'No' "
				+ "WHERE payment_id = ?", admin(), paymentId);
		jdbc.update(request, paymentId, orderId, "SYSTEM", null);
	}

	@Test
	void reconciliationCaseResolutionIsRecordedAndOpenCasesAreUnique() {
		UUID adminId = admin();
		UUID orderId = order(user(), "ONLINE", "EXPIRED");
		UUID paymentId = payment(orderId, "ONLINE", "SUCCEEDED");
		String open = """
				INSERT INTO payment_reconciliation_cases (payment_id, order_id, case_type)
				VALUES (?, ?, 'LATE_SUCCESS') RETURNING id""";

		UUID caseId = jdbc.queryForObject(open, UUID.class, paymentId, orderId);
		assertRejectedBy("uq_reconciliation_open", () -> jdbc.queryForObject(open, UUID.class, paymentId, orderId));
		assertRejectedWith(CHECK_VIOLATION, "payment_reconciliation_cases",
				() -> jdbc.update("UPDATE payment_reconciliation_cases SET status = 'RESOLVED' WHERE id = ?", caseId));
		jdbc.update("""
				UPDATE payment_reconciliation_cases
				SET status = 'RESOLVED', resolution = 'REFUND_REQUESTED', resolved_by = ?, resolved_at = now(),
				    resolution_note = 'Slot full; refund' WHERE id = ?""", adminId, caseId);
	}

}
