package com.bakedandbrowned.payment.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bakedandbrowned.audit.service.AuditLog;
import com.bakedandbrowned.common.web.ApiException;
import com.bakedandbrowned.ordering.service.OrderPaymentOutcomes;
import com.bakedandbrowned.ordering.service.OrderPaymentOutcomes.PaymentOrder;
import com.bakedandbrowned.payment.dto.PaymentDtos.AdminPayment;
import com.bakedandbrowned.payment.dto.PaymentDtos.PaymentVerificationRequest.Decision;

import tools.jackson.databind.json.JsonMapper;

/**
 * Independent ADMIN payment decisions (ADR-0011), always with a note, stored on the payment and in
 * the audit log:
 * <ul>
 * <li>MANUAL, order waiting for payment: APPROVE → payment SUCCEEDED, order CONFIRMED, holds
 * confirmed. REJECT → payment FAILED, order CANCELLED, holds released.</li>
 * <li>MANUAL, order already EXPIRED or CANCELLED (a late payment): APPROVE → payment SUCCEEDED and
 * a LATE_SUCCESS reconciliation case, never an automatic confirmation (ADR-0008). REJECT of the
 * open submission → recorded, nothing else changes.</li>
 * <li>COD: APPROVE records the cash collection (payment SUCCEEDED); REJECT is not allowed.</li>
 * <li>ONLINE: never; gateway results are verified server-to-server.</li>
 * </ul>
 * Lock order: the order row (shared with cancellation and expiry), then the payment, then the
 * submission.
 */
@Service
public class PaymentVerification {

	public static final String VERIFICATION_NOT_ALLOWED = "VERIFICATION_NOT_ALLOWED";

	public static final String PAYMENT_ALREADY_DECIDED = "PAYMENT_ALREADY_DECIDED";

	public static final String PAYMENT_SUPERSEDED = "PAYMENT_SUPERSEDED";

	public static final String INVALID_STATE = "INVALID_STATE";

	static final String REJECTED_REASON = "Manual payment rejected after verification.";

	private final JdbcTemplate jdbc;

	private final OrderPaymentOutcomes orders;

	private final AdminPaymentQueries queries;

	private final AuditLog audit;

	private final JsonMapper json;

	private final Clock clock;

	public PaymentVerification(JdbcTemplate jdbc, OrderPaymentOutcomes orders, AdminPaymentQueries queries,
			AuditLog audit, JsonMapper json, Clock clock) {
		this.jdbc = jdbc;
		this.orders = orders;
		this.queries = queries;
		this.audit = audit;
		this.json = json;
		this.clock = clock;
	}

	@Transactional
	public AdminPayment verify(UUID paymentId, UUID adminId, Decision decision, String note) {
		String trimmedNote = note.strip();
		List<UUID> orderIds = jdbc.queryForList("SELECT order_id FROM payments WHERE id = ?", UUID.class, paymentId);
		if (orderIds.isEmpty()) {
			throw ApiException.notFound(AdminPaymentQueries.PAYMENT_NOT_FOUND, "Payment not found.");
		}
		Instant now = clock.instant();
		PaymentOrder order = orders.lock(orderIds.get(0), now).orElseThrow();
		Map<String, Object> payment = jdbc.queryForMap("""
				SELECT method::text AS method, status::text AS status, superseded_at IS NOT NULL AS superseded
				FROM payments WHERE id = ? FOR UPDATE""", paymentId);
		if (Boolean.TRUE.equals(payment.get("superseded"))) {
			throw ApiException.conflict(PAYMENT_SUPERSEDED, "This payment was replaced by a newer one.");
		}
		String method = (String) payment.get("method");
		String status = (String) payment.get("status");
		UUID openSubmission = jdbc
			.queryForList("""
					SELECT id FROM manual_payment_submissions WHERE payment_id = ? AND status = 'SUBMITTED'
					FOR UPDATE""", UUID.class, paymentId)
			.stream()
			.findFirst()
			.orElse(null);

		switch (method) {
			case "COD" -> collectCash(paymentId, order, status, adminId, decision, trimmedNote, now);
			case "MANUAL" -> decideManual(paymentId, order, status, openSubmission, adminId, decision, trimmedNote, now);
			default -> throw ApiException.conflict(VERIFICATION_NOT_ALLOWED,
					"Online payments are verified with the payment provider, not by hand.");
		}
		return queries.get(paymentId);
	}

	private void collectCash(UUID paymentId, PaymentOrder order, String status, UUID adminId, Decision decision,
			String note, Instant now) {
		if (decision == Decision.REJECT) {
			throw ApiException.conflict(VERIFICATION_NOT_ALLOWED,
					"A cash-on-delivery payment cannot be rejected; cancel the order instead.");
		}
		if (!"PENDING".equals(status)) {
			throw alreadyDecided();
		}
		if ("CANCELLED".equals(order.status()) || "EXPIRED".equals(order.status())) {
			throw ApiException.conflict(INVALID_STATE, "Cash cannot be collected for a cancelled order.");
		}
		setOutcome(paymentId, "SUCCEEDED", null, adminId, note, now);
		audit(adminId, "payment.cod_collected", paymentId, note, order, null);
	}

	private void decideManual(UUID paymentId, PaymentOrder order, String status, @Nullable UUID openSubmission,
			UUID adminId, Decision decision, String note, Instant now) {
		boolean waiting = "PENDING".equals(status) && "PENDING_PAYMENT".equals(order.status());
		boolean late = "CANCELLED".equals(status)
				&& ("EXPIRED".equals(order.status()) || "CANCELLED".equals(order.status()));
		if (waiting && decision == Decision.APPROVE) {
			closeSubmission(openSubmission, "VERIFIED");
			setOutcome(paymentId, "SUCCEEDED", null, adminId, note, now);
			orders.confirmPaid(order.id(), adminId);
			audit(adminId, "payment.manual_approved", paymentId, note, order, openSubmission);
		}
		else if (waiting) {
			closeSubmission(openSubmission, "REJECTED");
			orders.cancelUnpaid(order.id(), adminId, note);
			setOutcome(paymentId, "FAILED", REJECTED_REASON, adminId, note, now);
			audit(adminId, "payment.manual_rejected", paymentId, note, order, openSubmission);
		}
		else if (late && decision == Decision.APPROVE) {
			// The money arrived after the order was closed: no oversell, an ADMIN resolves the case.
			closeSubmission(openSubmission, "VERIFIED");
			setOutcome(paymentId, "SUCCEEDED", null, adminId, note, now);
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("orderStatus", order.status());
			details.put("source", "MANUAL_VERIFICATION");
			if (openSubmission != null) {
				details.put("submissionId", openSubmission.toString());
			}
			jdbc.update("""
					INSERT INTO payment_reconciliation_cases (payment_id, order_id, case_type, details)
					VALUES (?, ?, 'LATE_SUCCESS', ?::jsonb)""", paymentId, order.id(), json.writeValueAsString(details));
			audit(adminId, "payment.manual_approved_late", paymentId, note, order, openSubmission);
		}
		else if (late && openSubmission != null) {
			closeSubmission(openSubmission, "REJECTED");
			jdbc.update("""
					UPDATE payments SET verified_by = ?, verified_at = ?, verification_note = ? WHERE id = ?""",
					adminId, Timestamp.from(now), note, paymentId);
			audit(adminId, "payment.manual_rejected_late", paymentId, note, order, openSubmission);
		}
		else {
			throw alreadyDecided();
		}
	}

	private void closeSubmission(@Nullable UUID submissionId, String outcome) {
		if (submissionId != null) {
			jdbc.update("UPDATE manual_payment_submissions SET status = ?::manual_submission_status WHERE id = ?",
					outcome, submissionId);
		}
	}

	private void setOutcome(UUID paymentId, String status, @Nullable String failureReason, UUID adminId, String note,
			Instant now) {
		jdbc.update("""
				UPDATE payments SET status = ?::payment_status, failure_reason = ?, verified_by = ?, verified_at = ?,
				                    verification_note = ?
				WHERE id = ?""", status, failureReason, adminId, Timestamp.from(now), note, paymentId);
	}

	private void audit(UUID adminId, String action, UUID paymentId, String note, PaymentOrder order,
			@Nullable UUID submissionId) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("orderId", order.id().toString());
		details.put("orderNumber", order.orderNumber());
		details.put("orderStatusBefore", order.status());
		if (submissionId != null) {
			details.put("submissionId", submissionId.toString());
		}
		audit.admin(adminId, action, "payment", paymentId, note, details);
	}

	private static ApiException alreadyDecided() {
		return ApiException.conflict(PAYMENT_ALREADY_DECIDED, "A decision has already been recorded for this payment.");
	}

}
