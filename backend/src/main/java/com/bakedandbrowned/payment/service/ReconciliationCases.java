package com.bakedandbrowned.payment.service;

import java.sql.ResultSet;
import java.sql.SQLException;
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
import com.bakedandbrowned.payment.dto.PaymentDtos.ReconciliationCase;
import com.bakedandbrowned.payment.dto.PaymentDtos.ReconciliationResolutionRequest.Action;

/**
 * ADMIN review of reconciliation cases (ADR-0008): list, and resolve once with a note.
 * <ul>
 * <li>REINSTATE: only for a received, current payment whose order is EXPIRED (fresh slot and stock
 * reservations under locks, or 409 if no longer available) or still PENDING_PAYMENT because it was
 * held for review (its holds were never released). The order becomes CONFIRMED.</li>
 * <li>REQUEST_REFUND: opens a refund request over what is left of the payment; a second ADMIN
 * approves it (ADR-0017).</li>
 * <li>NO_ACTION: closes the case, for example after settling a mismatch with the provider.</li>
 * </ul>
 * Lock order: the order row, then the case, then the payment.
 */
@Service
public class ReconciliationCases {

	public static final String CASE_NOT_FOUND = "CASE_NOT_FOUND";

	public static final String CASE_ALREADY_RESOLVED = "CASE_ALREADY_RESOLVED";

	public static final String REINSTATE_NOT_ALLOWED = "REINSTATE_NOT_ALLOWED";

	private static final String COLUMNS = """
			c.id, c.payment_id, c.order_id, o.order_number, c.case_type::text AS case_type, c.status::text AS status,
			c.details->>'reason' AS reason, c.resolution, c.resolution_note, c.resolved_at, c.created_at
			FROM payment_reconciliation_cases c JOIN orders o ON o.id = c.order_id""";

	private final JdbcTemplate jdbc;

	private final OrderPaymentOutcomes orders;

	private final RefundWorkflow refunds;

	private final AuditLog audit;

	private final Clock clock;

	public ReconciliationCases(JdbcTemplate jdbc, OrderPaymentOutcomes orders, RefundWorkflow refunds, AuditLog audit,
			Clock clock) {
		this.jdbc = jdbc;
		this.orders = orders;
		this.refunds = refunds;
		this.audit = audit;
		this.clock = clock;
	}

	/** Open cases oldest first (the work queue); otherwise newest first. At most 500. */
	@Transactional(readOnly = true)
	public List<ReconciliationCase> list(@Nullable String status) {
		if (status == null) {
			return jdbc.query("SELECT " + COLUMNS + " ORDER BY c.created_at DESC, c.id LIMIT 500", (rs, row) -> map(rs));
		}
		String order = "OPEN".equals(status) ? "c.created_at, c.id" : "c.created_at DESC, c.id";
		return jdbc.query("SELECT " + COLUMNS + " WHERE c.status = ?::reconciliation_case_status ORDER BY " + order
				+ " LIMIT 500", (rs, row) -> map(rs), status);
	}

	@Transactional
	public ReconciliationCase resolve(UUID caseId, UUID adminId, Action action, String note) {
		String trimmed = note.strip();
		Instant now = clock.instant();
		List<UUID> orderIds = jdbc.queryForList("SELECT order_id FROM payment_reconciliation_cases WHERE id = ?",
				UUID.class, caseId);
		if (orderIds.isEmpty()) {
			throw ApiException.notFound(CASE_NOT_FOUND, "Reconciliation case not found.");
		}
		PaymentOrder order = orders.lock(orderIds.get(0), now).orElseThrow();
		Map<String, Object> found = jdbc.queryForMap("""
				SELECT payment_id, case_type::text AS case_type, status::text AS status
				FROM payment_reconciliation_cases WHERE id = ? FOR UPDATE""", caseId);
		if (!"OPEN".equals(found.get("status"))) {
			throw ApiException.conflict(CASE_ALREADY_RESOLVED, "This case has already been resolved.");
		}
		UUID paymentId = (UUID) found.get("payment_id");
		String resolution = switch (action) {
			case REINSTATE -> {
				reinstate(order, paymentId, adminId, trimmed, now);
				yield "REINSTATED";
			}
			case REQUEST_REFUND -> {
				refunds.request(paymentId, adminId, trimmed, null);
				yield "REFUND_REQUESTED";
			}
			case NO_ACTION -> "NO_ACTION";
		};
		jdbc.update("""
				UPDATE payment_reconciliation_cases
				SET status = 'RESOLVED', resolution = ?, resolved_by = ?, resolved_at = ?, resolution_note = ?
				WHERE id = ?""", resolution, adminId, Timestamp.from(now), trimmed, caseId);
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("caseType", found.get("case_type"));
		details.put("resolution", resolution);
		details.put("orderId", order.id().toString());
		details.put("orderNumber", order.orderNumber());
		details.put("orderStatusBefore", order.status());
		details.put("paymentId", paymentId.toString());
		audit.admin(adminId, "reconciliation.resolved", "reconciliation_case", caseId, trimmed, details);
		return jdbc.query("SELECT " + COLUMNS + " WHERE c.id = ?", (rs, row) -> map(rs), caseId).getFirst();
	}

	private void reinstate(PaymentOrder order, UUID paymentId, UUID adminId, String note, Instant now) {
		Map<String, Object> payment = jdbc.queryForMap("""
				SELECT status::text AS status, superseded_at IS NOT NULL AS superseded FROM payments
				WHERE id = ? FOR UPDATE""", paymentId);
		if (!"SUCCEEDED".equals(payment.get("status")) || Boolean.TRUE.equals(payment.get("superseded"))) {
			throw ApiException.conflict(REINSTATE_NOT_ALLOWED,
					"Only an order whose current payment was received can be reinstated.");
		}
		switch (order.status()) {
			case "EXPIRED" -> orders.reinstatePaid(order.id(), adminId, note, now);
			case "PENDING_PAYMENT" -> orders.confirmPaid(order.id(), adminId);
			default -> throw ApiException.conflict(REINSTATE_NOT_ALLOWED,
					"Only an expired order, or one held for review, can be reinstated; this one is "
							+ order.status().toLowerCase(java.util.Locale.ROOT) + ".");
		}
	}

	private static ReconciliationCase map(ResultSet rs) throws SQLException {
		Timestamp resolvedAt = rs.getTimestamp("resolved_at");
		return new ReconciliationCase(rs.getObject("id", UUID.class), rs.getObject("payment_id", UUID.class),
				rs.getObject("order_id", UUID.class), rs.getString("order_number"), rs.getString("case_type"),
				rs.getString("status"), rs.getString("reason"), rs.getString("resolution"),
				rs.getString("resolution_note"), resolvedAt == null ? null : resolvedAt.toInstant(),
				rs.getTimestamp("created_at").toInstant());
	}

}
