package com.bakedandbrowned.payment.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bakedandbrowned.payment.gateway.PaymentGateway;
import com.bakedandbrowned.payment.gateway.PaymentGateways;
import com.bakedandbrowned.payment.gateway.PaymentVerificationException;
import com.bakedandbrowned.payment.service.GatewayResults.Outcome;

import tools.jackson.databind.json.JsonMapper;

/**
 * Asks gateways about ONLINE payments whose outcome may have been missed (a lost or late callback),
 * and applies the answer exactly as a callback would ({@link GatewayResults}). Candidates are
 * current payments with a session that are still PENDING, or CANCELLED (because the order expired
 * or was cancelled, so a late success is still caught), between {@code min-age} and
 * {@code lookback} old.
 * <ul>
 * <li>Each payment is claimed by one guarded {@code UPDATE} of {@code gateway_checked_at} before
 * the gateway call, so concurrent workers never ask about the same payment at once; the call runs
 * outside any transaction.</li>
 * <li>Checks back off: at least {@code recheck} apart, growing to a quarter of the payment's age.</li>
 * <li>A payment that ages out of the window while its last check failed becomes one AMBIGUOUS_STATUS
 * case (reason GATEWAY_STATUS_UNKNOWN). A clean PENDING answer (an abandoned checkout) does not.</li>
 * </ul>
 */
@Service
@EnableConfigurationProperties(ReconciliationProperties.class)
public class PaymentReconciliation {

	private static final Logger log = LoggerFactory.getLogger(PaymentReconciliation.class);

	/** Unsettled current ONLINE payments with a session. */
	private static final String UNSETTLED = """
			p.method = 'ONLINE' AND p.provider_session_id IS NOT NULL AND p.superseded_at IS NULL
			AND p.status IN ('PENDING', 'CANCELLED')""";

	/** Parameters: now, min-age s, now, lookback s, now, recheck s, now. */
	private static final String DUE = UNSETTLED + """
			 AND p.created_at <= ?::timestamptz - ? * INTERVAL '1 second'
			 AND p.created_at > ?::timestamptz - ? * INTERVAL '1 second'
			 AND (p.gateway_checked_at IS NULL OR p.gateway_checked_at <= ?::timestamptz
			      - GREATEST(? * INTERVAL '1 second', (?::timestamptz - p.created_at) / 4))""";

	private final JdbcTemplate jdbc;

	private final PaymentGateways gateways;

	private final GatewayResults results;

	private final ReconciliationProperties properties;

	private final JsonMapper json;

	private final Clock clock;

	public PaymentReconciliation(JdbcTemplate jdbc, PaymentGateways gateways, GatewayResults results,
			ReconciliationProperties properties, JsonMapper json, Clock clock) {
		this.jdbc = jdbc;
		this.gateways = gateways;
		this.results = results;
		this.properties = properties;
		this.json = json;
		this.clock = clock;
	}

	/** Up to {@code limit} payments due a check now, least recently checked first (no locks). */
	@Transactional(readOnly = true)
	public List<UUID> due(int limit) {
		List<Object> params = dueParams(clock.instant());
		params.add(limit);
		return jdbc.queryForList("SELECT p.id FROM payments p WHERE " + DUE
				+ " ORDER BY coalesce(p.gateway_checked_at, p.created_at), p.id LIMIT ?", UUID.class, params.toArray());
	}

	/**
	 * Claims the payment if it is still due, asks its gateway, and applies the answer. Must not be
	 * called inside a transaction.
	 * @return what applying did, or empty if the payment was not due, or the gateway could not answer
	 */
	public Optional<Outcome> reconcile(UUID paymentId) {
		Instant now = clock.instant();
		List<Object> params = dueParams(now);
		params.add(0, Timestamp.from(now));
		params.add(paymentId);
		List<Map<String, Object>> claimed = jdbc.queryForList("UPDATE payments p SET gateway_checked_at = ? WHERE "
				+ DUE + " AND p.id = ? RETURNING p.provider, p.provider_session_id", params.toArray());
		if (claimed.isEmpty()) {
			return Optional.empty();
		}
		String provider = (String) claimed.get(0).get("provider");
		String sessionId = (String) claimed.get(0).get("provider_session_id");
		Optional<PaymentGateway> gateway = provider == null ? Optional.empty() : gateways.byProvider(provider);
		if (gateway.isEmpty()) {
			recordError(paymentId, "No gateway adapter for provider " + provider);
			return Optional.empty();
		}
		try {
			Outcome outcome = results.apply(gateway.get().queryStatus(sessionId));
			jdbc.update("UPDATE payments SET gateway_check_error = NULL WHERE id = ?", paymentId);
			if (outcome != Outcome.NO_CHANGE && outcome != Outcome.DUPLICATE) {
				log.info("Reconciled payment {} with {}: {}", paymentId, provider, outcome);
			}
			return Optional.of(outcome);
		}
		catch (PaymentVerificationException ex) {
			recordError(paymentId, java.util.Objects.requireNonNullElse(ex.getMessage(), ex.getClass().getSimpleName()));
			return Optional.empty();
		}
	}

	/**
	 * Payments that aged out of the window while their last check failed become AMBIGUOUS_STATUS
	 * cases, once each (also on later runs: a resolved case is never reopened).
	 * @return how many cases were opened
	 */
	@Transactional
	public int closeOutUnresolved(int limit) {
		Instant now = clock.instant();
		List<Map<String, Object>> stale = jdbc.queryForList("SELECT p.id, p.order_id, p.gateway_check_error FROM payments p WHERE "
				+ UNSETTLED + """
						 AND p.created_at <= ?::timestamptz - ? * INTERVAL '1 second'
						 AND p.gateway_check_error IS NOT NULL
						 AND NOT EXISTS (SELECT 1 FROM payment_reconciliation_cases c
						                 WHERE c.payment_id = p.id AND c.case_type = 'AMBIGUOUS_STATUS')
						ORDER BY p.created_at LIMIT ?""", Timestamp.from(now), properties.lookback().toSeconds(), limit);
		int opened = 0;
		for (Map<String, Object> p : stale) {
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("reason", "GATEWAY_STATUS_UNKNOWN");
			details.put("lastError", p.get("gateway_check_error"));
			opened += jdbc.update("""
					INSERT INTO payment_reconciliation_cases (payment_id, order_id, case_type, details)
					VALUES (?, ?, 'AMBIGUOUS_STATUS', ?::jsonb)
					ON CONFLICT (payment_id, case_type) WHERE status = 'OPEN' DO NOTHING""", p.get("id"),
					p.get("order_id"), json.writeValueAsString(details));
		}
		if (opened > 0) {
			log.warn("Opened {} reconciliation case(s) for payments the gateway never answered about", opened);
		}
		return opened;
	}

	int batchSize() {
		return properties.batchSize();
	}

	private void recordError(UUID paymentId, String message) {
		String trimmed = message.length() > 200 ? message.substring(0, 200) : message;
		jdbc.update("UPDATE payments SET gateway_check_error = ? WHERE id = ?", trimmed, paymentId);
		log.warn("Could not reconcile payment {}: {}", paymentId, trimmed);
	}

	private List<Object> dueParams(Instant now) {
		Timestamp at = Timestamp.from(now);
		return new java.util.ArrayList<>(List.of(at, properties.minAge().toSeconds(), at,
				properties.lookback().toSeconds(), at, properties.recheck().toSeconds(), at));
	}

}
