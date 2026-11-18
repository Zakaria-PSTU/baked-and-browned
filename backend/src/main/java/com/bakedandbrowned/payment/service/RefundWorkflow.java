package com.bakedandbrowned.payment.service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bakedandbrowned.audit.service.AuditLog;
import com.bakedandbrowned.common.outbox.OutboxPublisher;
import com.bakedandbrowned.common.value.Money;
import com.bakedandbrowned.common.web.ApiException;
import com.bakedandbrowned.common.web.ApiExceptionHandler;
import com.bakedandbrowned.ordering.service.OrderPaymentOutcomes;
import com.bakedandbrowned.payment.dto.PaymentDtos.PaymentVerificationRequest.Decision;
import com.bakedandbrowned.payment.dto.PaymentDtos.Refund;

/**
 * Refunds (ADR-0011, ADR-0017): REQUESTED → APPROVED or REJECTED (note required; never approved by
 * the ADMIN who requested it) → COMPLETED when an ADMIN records the external refund reference.
 * Refunds may be partial; together they never exceed the payment, and the payment becomes REFUNDED
 * once they add up to it. The order's status is not changed by any step. Every step is audited.
 * <p>
 * Lock order matches every other order change: the order row, then the payment, then the refund,
 * so a step cannot interleave with a cancellation that opens a request for the same payment.
 */
@Service
public class RefundWorkflow {

	public static final String REFUND_NOT_FOUND = "REFUND_NOT_FOUND";

	public static final String PAYMENT_NOT_REFUNDABLE = "PAYMENT_NOT_REFUNDABLE";

	public static final String REFUND_ALREADY_OPEN = "REFUND_ALREADY_OPEN";

	public static final String REFUND_ALREADY_DECIDED = "REFUND_ALREADY_DECIDED";

	public static final String REFUND_NOT_APPROVED = "REFUND_NOT_APPROVED";

	public static final String SECOND_APPROVER_REQUIRED = "SECOND_APPROVER_REQUIRED";

	public static final String REFUND_EXCEEDS_REMAINING = "REFUND_EXCEEDS_REMAINING";

	/** Upper bound on the list (a plain array in the contract). */
	static final int MAX_LISTED = 500;

	private final JdbcTemplate jdbc;

	private final OrderPaymentOutcomes orders;

	private final AuditLog audit;

	private final OutboxPublisher outbox;

	private final Clock clock;

	public RefundWorkflow(JdbcTemplate jdbc, OrderPaymentOutcomes orders, AuditLog audit, OutboxPublisher outbox,
			Clock clock) {
		this.jdbc = jdbc;
		this.orders = orders;
		this.audit = audit;
		this.outbox = outbox;
		this.clock = clock;
	}

	/** Newest first, optionally one status. */
	@Transactional(readOnly = true)
	public List<Refund> list(@Nullable String status) {
		String base = "SELECT " + AdminPaymentQueries.REFUND_COLUMNS + " FROM refunds";
		String order = " ORDER BY created_at DESC, id DESC LIMIT ?";
		return status == null ? jdbc.query(base + order, (rs, row) -> AdminPaymentQueries.refund(rs), MAX_LISTED)
				: jdbc.query(base + " WHERE status = ?::refund_status" + order,
						(rs, row) -> AdminPaymentQueries.refund(rs), status, MAX_LISTED);
	}

	/**
	 * Opens a refund request for the payment, for example after a complaint about a delivered
	 * order; another ADMIN must approve it. Cancellations open theirs automatically.
	 * @param amount a partial amount, or null for everything not yet refunded
	 * @throws ApiException 404 for an unknown payment; 409 when the payment has not succeeded or is
	 * fully refunded, a request is already open, or the amount exceeds what is left; 422 for a zero
	 * amount
	 */
	@Transactional
	public Refund request(UUID paymentId, UUID adminId, String reason, @Nullable Money amount) {
		requireActiveAdmin(adminId);
		String note = reason.strip();
		UUID orderId = orderOfPayment(paymentId);
		orders.lock(orderId, clock.instant());
		Map<String, Object> payment = jdbc.queryForMap("""
				SELECT status::text AS status, amount, superseded_at IS NOT NULL AS superseded
				FROM payments WHERE id = ? FOR UPDATE""", paymentId);
		if (!"SUCCEEDED".equals(payment.get("status")) || Boolean.TRUE.equals(payment.get("superseded"))) {
			throw ApiException.conflict(PAYMENT_NOT_REFUNDABLE, "Only a received payment can be refunded.");
		}
		Integer open = jdbc.queryForObject(
				"SELECT count(*) FROM refunds WHERE payment_id = ? AND status IN ('REQUESTED', 'APPROVED')",
				Integer.class, paymentId);
		if (open != null && open > 0) {
			throw alreadyOpen();
		}
		BigDecimal remaining = ((BigDecimal) payment.get("amount")).subtract(completedTotal(paymentId));
		if (remaining.signum() <= 0) {
			throw ApiException.conflict(PAYMENT_NOT_REFUNDABLE, "This payment has been refunded in full.");
		}
		BigDecimal requested = amount == null ? remaining : amount.amount();
		if (requested.signum() <= 0) {
			throw ApiException.unprocessable(ApiExceptionHandler.VALIDATION_FAILED, "amount: must be greater than 0");
		}
		if (requested.compareTo(remaining) > 0) {
			throw ApiException.conflict(REFUND_EXCEEDS_REMAINING,
					"At most BDT " + remaining.toPlainString() + " of this payment can still be refunded.");
		}
		UUID refundId;
		try {
			refundId = jdbc.queryForObject("""
					INSERT INTO refunds (payment_id, order_id, amount, request_reason, requested_by_type, requested_by)
					VALUES (?, ?, ?, ?, 'ADMIN', ?) RETURNING id""", UUID.class, paymentId, orderId, requested, note,
					adminId);
		}
		catch (DuplicateKeyException ex) {
			// Backstop for the unique index; the order row lock already serializes requests.
			throw alreadyOpen();
		}
		audit(adminId, "refund.requested", refundId, note, orderId, paymentId);
		return get(refundId);
	}

	/**
	 * Review of a request. Second approver: the ADMIN who requested a refund may withdraw it
	 * (REJECT) but never approve it; another ADMIN must.
	 * @throws ApiException 404 for an unknown refund; 409 {@link #REFUND_ALREADY_DECIDED} or
	 * {@link #SECOND_APPROVER_REQUIRED}
	 */
	@Transactional
	public Refund decide(UUID refundId, UUID adminId, Decision decision, String note) {
		requireActiveAdmin(adminId);
		String trimmed = note.strip();
		Map<String, Object> refund = lockRefund(refundId);
		if (!"REQUESTED".equals(refund.get("status"))) {
			throw ApiException.conflict(REFUND_ALREADY_DECIDED, "A decision has already been recorded for this refund.");
		}
		if (decision == Decision.APPROVE && adminId.equals(refund.get("requested_by"))) {
			throw ApiException.conflict(SECOND_APPROVER_REQUIRED,
					"Another administrator must approve a refund you requested.");
		}
		String outcome = decision == Decision.APPROVE ? "APPROVED" : "REJECTED";
		jdbc.update("""
				UPDATE refunds SET status = ?::refund_status, decided_by = ?, decided_at = ?, decision_note = ?
				WHERE id = ?""", outcome, adminId, Timestamp.from(clock.instant()), trimmed, refundId);
		audit(adminId, decision == Decision.APPROVE ? "refund.approved" : "refund.rejected", refundId, trimmed,
				(UUID) refund.get("order_id"), (UUID) refund.get("payment_id"));
		return get(refundId);
	}

	/**
	 * Records that the money was sent back: refund COMPLETED, and the payment REFUNDED once its
	 * completed refunds add up to the full amount (a partial refund leaves it SUCCEEDED). The
	 * customer is told by SMS ({@link PaymentEvents#REFUND_COMPLETED}).
	 * @throws ApiException 404 for an unknown refund; 409 {@link #REFUND_NOT_APPROVED} unless the
	 * refund is APPROVED
	 */
	@Transactional
	public Refund complete(UUID refundId, UUID adminId, String externalReference) {
		requireActiveAdmin(adminId);
		String reference = externalReference.strip();
		Map<String, Object> refund = lockRefund(refundId);
		if (!"APPROVED".equals(refund.get("status"))) {
			throw ApiException.conflict(REFUND_NOT_APPROVED,
					"Only an approved refund can be completed (this one is " + refund.get("status") + ").");
		}
		Instant now = clock.instant();
		UUID paymentId = (UUID) refund.get("payment_id");
		UUID orderId = (UUID) refund.get("order_id");
		jdbc.update("""
				UPDATE refunds SET status = 'COMPLETED', external_reference = ?, completed_at = ? WHERE id = ?""",
				reference, Timestamp.from(now), refundId);
		BigDecimal paid = jdbc.queryForObject("SELECT amount FROM payments WHERE id = ? AND status = 'SUCCEEDED'",
				BigDecimal.class, paymentId);
		BigDecimal refundedTotal = completedTotal(paymentId);
		if (paid == null || refundedTotal.compareTo(paid) > 0) {
			throw new IllegalStateException("Refunds of payment " + paymentId + " exceed what was paid");
		}
		boolean full = refundedTotal.compareTo(paid) == 0;
		if (full) {
			jdbc.update("UPDATE payments SET status = 'REFUNDED' WHERE id = ?", paymentId);
		}
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("orderId", orderId.toString());
		details.put("paymentId", paymentId.toString());
		details.put("externalReference", reference);
		details.put("amount", ((BigDecimal) refund.get("amount")).toPlainString());
		details.put("paymentFullyRefunded", full);
		audit.admin(adminId, "refund.completed", "refund", refundId, null, details);
		outbox.publish(PaymentEvents.REFUND_AGGREGATE, refundId, PaymentEvents.REFUND_COMPLETED,
				Map.of("refundId", refundId.toString(), "orderId", orderId.toString()));
		return get(refundId);
	}

	/**
	 * Money moves on these decisions, so the token's role is not enough: the account must be an
	 * enabled ADMIN right now (a demoted or disabled ADMIN's unexpired token is refused).
	 */
	private void requireActiveAdmin(UUID adminId) {
		boolean active = jdbc
			.queryForList("SELECT role = 'ADMIN' AND enabled FROM users WHERE id = ?", Boolean.class, adminId)
			.stream()
			.findFirst()
			.orElse(false);
		if (!active) {
			throw ApiException.forbidden("FORBIDDEN", "You do not have permission to perform this action.");
		}
	}

	/** Total of the payment's completed refunds. */
	private BigDecimal completedTotal(UUID paymentId) {
		BigDecimal total = jdbc.queryForObject(
				"SELECT coalesce(sum(amount), 0) FROM refunds WHERE payment_id = ? AND status = 'COMPLETED'",
				BigDecimal.class, paymentId);
		return total == null ? BigDecimal.ZERO : total;
	}

	/** Locks the order, then the payment, then the refund; returns the refund row. */
	private Map<String, Object> lockRefund(UUID refundId) {
		List<Map<String, Object>> found = jdbc.queryForList("SELECT order_id, payment_id FROM refunds WHERE id = ?",
				refundId);
		if (found.isEmpty()) {
			throw ApiException.notFound(REFUND_NOT_FOUND, "Refund not found.");
		}
		orders.lock((UUID) found.get(0).get("order_id"), clock.instant());
		jdbc.queryForList("SELECT id FROM payments WHERE id = ? FOR UPDATE", found.get(0).get("payment_id"));
		return jdbc.queryForMap("""
				SELECT order_id, payment_id, amount, requested_by, status::text AS status FROM refunds WHERE id = ? FOR UPDATE""", refundId);
	}

	private static ApiException alreadyOpen() {
		return ApiException.conflict(REFUND_ALREADY_OPEN, "This payment already has a refund in progress.");
	}

	private UUID orderOfPayment(UUID paymentId) {
		return jdbc.queryForList("SELECT order_id FROM payments WHERE id = ?", UUID.class, paymentId)
			.stream()
			.findFirst()
			.orElseThrow(() -> ApiException.notFound(AdminPaymentQueries.PAYMENT_NOT_FOUND, "Payment not found."));
	}

	private Refund get(UUID refundId) {
		return jdbc.queryForObject("SELECT " + AdminPaymentQueries.REFUND_COLUMNS + " FROM refunds WHERE id = ?",
				(rs, row) -> AdminPaymentQueries.refund(rs), refundId);
	}

	private void audit(UUID adminId, String action, UUID refundId, String note, UUID orderId, UUID paymentId) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("orderId", orderId.toString());
		details.put("paymentId", paymentId.toString());
		audit.admin(adminId, action, "refund", refundId, note, details);
	}

}
