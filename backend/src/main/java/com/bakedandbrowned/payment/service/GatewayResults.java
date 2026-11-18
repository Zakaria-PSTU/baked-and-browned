package com.bakedandbrowned.payment.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bakedandbrowned.audit.service.AuditLog;
import com.bakedandbrowned.common.value.Money;
import com.bakedandbrowned.ordering.service.OrderPaymentOutcomes;
import com.bakedandbrowned.ordering.service.OrderPaymentOutcomes.PaymentOrder;
import com.bakedandbrowned.payment.gateway.GatewayPaymentStatus;
import com.bakedandbrowned.payment.gateway.VerifiedPaymentResult;

import tools.jackson.databind.json.JsonMapper;

/**
 * Applies a gateway's server-to-server verified result to our payment (business rules, Payment;
 * ADR-0008, ADR-0012). Shared by webhooks and, later, reconciliation; one short transaction, never
 * with a gateway call inside it.
 * <ul>
 * <li>Each provider state is applied once: {@code payment_webhook_events (provider, event_id)} is
 * claimed first, so duplicate or concurrent deliveries of the same result do nothing.</li>
 * <li>Lock order as everywhere else: the order row (shared with cancellation and expiry), then the
 * payment. The event row is claimed after the order lock, because its foreign key share-locks the
 * payment (a race with hold expiry deadlocked otherwise).</li>
 * <li>SUCCEEDED must match the order number, the payment amount and BDT; otherwise nothing changes
 * and an AMOUNT_MISMATCH case is opened.</li>
 * <li>SUCCEEDED for an order still waiting for payment confirms it (its holds were never released,
 * so this cannot oversell). For an EXPIRED or CANCELLED order it records the money and opens a
 * LATE_SUCCESS case, never an automatic confirmation.</li>
 * <li>FAILED or CANCELLED ends that attempt only: the order keeps waiting, so the customer can
 * retry within the hold, and hold expiry releases it otherwise.</li>
 * <li>Anything contradictory (success on a replaced attempt, failure after success) changes
 * nothing and opens an AMBIGUOUS_STATUS case for an ADMIN.</li>
 * </ul>
 */
@Service
public class GatewayResults {

	/** What applying a result did; for logs and tests. */
	public enum Outcome {

		DUPLICATE, UNKNOWN_SESSION, NO_CHANGE, CONFIRMED, ATTEMPT_ENDED, LATE_SUCCESS, AMOUNT_MISMATCH, AMBIGUOUS

	}

	private static final Logger log = LoggerFactory.getLogger(GatewayResults.class);

	private final JdbcTemplate jdbc;

	private final OrderPaymentOutcomes orders;

	private final AuditLog audit;

	private final JsonMapper json;

	private final Clock clock;

	public GatewayResults(JdbcTemplate jdbc, OrderPaymentOutcomes orders, AuditLog audit, JsonMapper json,
			Clock clock) {
		this.jdbc = jdbc;
		this.orders = orders;
		this.audit = audit;
		this.json = json;
		this.clock = clock;
	}

	@Transactional
	public Outcome apply(VerifiedPaymentResult result) {
		Instant now = clock.instant();
		List<Map<String, Object>> found = jdbc.queryForList(
				"SELECT id, order_id FROM payments WHERE provider = ? AND provider_session_id = ?", result.provider(),
				result.sessionId());
		UUID paymentId = found.isEmpty() ? null : (UUID) found.get(0).get("id");
		// The order row first, before the event insert: its foreign key share-locks the payment row,
		// and cancellation and expiry lock the order and then the payment FOR UPDATE.
		@Nullable PaymentOrder order = paymentId == null ? null
				: orders.lock((UUID) found.get(0).get("order_id"), now).orElseThrow();
		int claimed = jdbc.update("""
				INSERT INTO payment_webhook_events (provider, event_id, payment_id, payload, processed_at)
				VALUES (?, ?, ?, ?::jsonb, ?) ON CONFLICT (provider, event_id) DO NOTHING""", result.provider(),
				result.eventId(), paymentId, json.writeValueAsString(payload(result)), Timestamp.from(now));
		if (claimed == 0) {
			return Outcome.DUPLICATE;
		}
		if (paymentId == null || order == null) {
			log.warn("Verified {} result for an unknown session; nothing to apply", result.provider());
			return Outcome.UNKNOWN_SESSION;
		}
		Map<String, Object> payment = jdbc.queryForMap("""
				SELECT status::text AS status, superseded_at IS NOT NULL AS superseded, amount
				FROM payments WHERE id = ? FOR UPDATE""", paymentId);
		Current current = new Current(paymentId, (String) payment.get("status"),
				Boolean.TRUE.equals(payment.get("superseded")), Money.of((java.math.BigDecimal) payment.get("amount")));
		Outcome outcome = switch (result.status()) {
			case PENDING -> Outcome.NO_CHANGE;
			case SUCCEEDED -> succeeded(result, order, current, now);
			case FAILED, CANCELLED -> ended(result, order, current);
		};
		if (outcome != Outcome.NO_CHANGE) {
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("orderId", order.id().toString());
			details.put("orderNumber", order.orderNumber());
			details.put("orderStatusBefore", order.status());
			details.put("paymentStatusBefore", current.status());
			details.put("gatewayStatus", result.status().name());
			details.put("eventId", result.eventId());
			audit.gateway("payment.gateway_" + outcome.name().toLowerCase(Locale.ROOT), "payment", paymentId, details);
		}
		return outcome;
	}

	private Outcome succeeded(VerifiedPaymentResult result, PaymentOrder order, Current payment, Instant now) {
		if (!result.confirms(order.orderNumber(), payment.amount())) {
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("reportedAmount", result.amount().amount());
			details.put("reportedCurrency", result.currency());
			details.put("expectedAmount", payment.amount().amount());
			details.put("referenceMatches", result.orderReference().equals(order.orderNumber()));
			details.put("transactionId", result.transactionId());
			openCase(payment.id(), order.id(), "AMOUNT_MISMATCH", details);
			return Outcome.AMOUNT_MISMATCH;
		}
		if ("SUCCEEDED".equals(payment.status()) || "REFUNDED".equals(payment.status())) {
			return Outcome.NO_CHANGE;
		}
		if (payment.superseded()) {
			openCase(payment.id(), order.id(), "AMBIGUOUS_STATUS",
					reason("SUCCESS_ON_REPLACED_ATTEMPT", result.transactionId()));
			return Outcome.AMBIGUOUS;
		}
		boolean waiting = "PENDING_PAYMENT".equals(order.status());
		boolean closed = "EXPIRED".equals(order.status()) || "CANCELLED".equals(order.status());
		if (!waiting && !closed) {
			openCase(payment.id(), order.id(), "AMBIGUOUS_STATUS",
					reason("SUCCESS_FOR_ORDER_" + order.status(), result.transactionId()));
			return Outcome.AMBIGUOUS;
		}
		jdbc.update("""
				UPDATE payments SET status = 'SUCCEEDED', provider_transaction_id = ?, failure_reason = NULL,
				                    captured_at = ?
				WHERE id = ?""", result.transactionId(), Timestamp.from(now), payment.id());
		if (waiting) {
			orders.confirmPaidByGateway(order.id());
			return Outcome.CONFIRMED;
		}
		Map<String, Object> details = reason("PAID_AFTER_ORDER_" + order.status(), result.transactionId());
		details.put("orderStatus", order.status());
		details.put("source", "GATEWAY");
		openCase(payment.id(), order.id(), "LATE_SUCCESS", details);
		return Outcome.LATE_SUCCESS;
	}

	private Outcome ended(VerifiedPaymentResult result, PaymentOrder order, Current payment) {
		if (payment.superseded()) {
			return Outcome.NO_CHANGE;
		}
		if ("SUCCEEDED".equals(payment.status())) {
			openCase(payment.id(), order.id(), "AMBIGUOUS_STATUS", reason(result.status() + "_AFTER_SUCCESS", null));
			return Outcome.AMBIGUOUS;
		}
		if (!"PENDING".equals(payment.status())) {
			return Outcome.NO_CHANGE;
		}
		String status = result.status() == GatewayPaymentStatus.FAILED ? "FAILED" : "CANCELLED";
		jdbc.update("UPDATE payments SET status = ?::payment_status, failure_reason = ? WHERE id = ?", status,
				result.status() == GatewayPaymentStatus.FAILED ? "The payment failed at the payment provider."
						: "The payment was cancelled on the payment page.",
				payment.id());
		return Outcome.ATTEMPT_ENDED;
	}

	/** Opens a case unless the same kind is already open for this payment. */
	private void openCase(UUID paymentId, UUID orderId, String type, Map<String, Object> details) {
		jdbc.update("""
				INSERT INTO payment_reconciliation_cases (payment_id, order_id, case_type, details)
				VALUES (?, ?, ?::reconciliation_case_type, ?::jsonb)
				ON CONFLICT (payment_id, case_type) WHERE status = 'OPEN' DO NOTHING""", paymentId, orderId, type,
				json.writeValueAsString(details));
		log.warn("Opened {} reconciliation case for payment {}", type, paymentId);
	}

	private static Map<String, Object> reason(String reason, @Nullable String transactionId) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("reason", reason);
		if (transactionId != null) {
			details.put("transactionId", transactionId);
		}
		return details;
	}

	/** What is kept of a result: the verified fields only, never the raw callback body. */
	private static Map<String, Object> payload(VerifiedPaymentResult result) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("sessionId", result.sessionId());
		payload.put("transactionId", result.transactionId());
		payload.put("orderReference", result.orderReference());
		payload.put("amount", result.amount().amount());
		payload.put("currency", result.currency());
		payload.put("status", result.status().name());
		return payload;
	}

	private record Current(UUID id, String status, boolean superseded, Money amount) {
	}

}
