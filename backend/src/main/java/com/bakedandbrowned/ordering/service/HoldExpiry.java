package com.bakedandbrowned.ordering.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bakedandbrowned.common.outbox.OutboxPublisher;
import com.bakedandbrowned.ordering.service.OrderClosing.Actor;

/**
 * Payment hold expiry (ADR-0008): PENDING_PAYMENT orders past {@code expires_at} become EXPIRED
 * and release their slot and stock holds. {@link HoldExpiryJob} drives it.
 * <p>
 * A MANUAL order whose customer submitted a payment reference within the hold waits for ADMIN
 * verification instead, until the review window ends (submission + {@code manual-review-window}
 * or slot start − {@code manual-review-slot-margin}, whichever is earlier).
 * <p>
 * Safe to run concurrently with itself and with cancellation or payment confirmation: each order
 * is expired in its own transaction that claims the row with {@code FOR UPDATE SKIP LOCKED} and
 * re-checks that it is still due, so it is expired and released at most once.
 */
@Service
@EnableConfigurationProperties(HoldExpiryProperties.class)
public class HoldExpiry {

	static final String EVENT_ORDER_EXPIRED = "order.expired";

	static final String REASON = "Payment was not completed within the hold period.";

	/**
	 * Still PENDING_PAYMENT, hold over, and no MANUAL review in progress. Parameters: now, now, review
	 * window seconds, slot margin seconds.
	 */
	private static final String DUE = """
			o.status = 'PENDING_PAYMENT' AND o.expires_at <= ?
			AND NOT EXISTS (
			  SELECT 1 FROM payments p
			  JOIN manual_payment_submissions m ON m.payment_id = p.id
			  JOIN delivery_slots s ON s.id = o.delivery_slot_id
			  WHERE p.order_id = o.id AND p.superseded_at IS NULL AND m.status = 'SUBMITTED'
			    AND m.created_at <= o.expires_at
			    AND ? < LEAST(m.created_at + ? * INTERVAL '1 second', s.start_at - ? * INTERVAL '1 second'))""";

	private final JdbcTemplate jdbc;

	private final OrderClosing closing;

	private final OutboxPublisher outbox;

	private final HoldExpiryProperties properties;

	private final Clock clock;

	public HoldExpiry(JdbcTemplate jdbc, OrderClosing closing, OutboxPublisher outbox, HoldExpiryProperties properties,
			Clock clock) {
		this.jdbc = jdbc;
		this.closing = closing;
		this.outbox = outbox;
		this.properties = properties;
		this.clock = clock;
	}

	/** Up to {@code limit} orders due now, oldest hold first (no locks taken). */
	@Transactional(readOnly = true)
	public List<UUID> due(int limit) {
		Timestamp now = Timestamp.from(clock.instant());
		return jdbc.queryForList("SELECT o.id FROM orders o WHERE " + DUE + " ORDER BY o.expires_at, o.id LIMIT ?",
				UUID.class, now, now, reviewWindowSeconds(), slotMarginSeconds(), limit);
	}

	/**
	 * Expires one order if it is still due and no other transaction is changing it right now.
	 * @return whether this call expired it
	 */
	@Transactional
	public boolean expire(UUID orderId) {
		Timestamp now = Timestamp.from(clock.instant());
		List<Map<String, Object>> claimed = jdbc.queryForList(
				"SELECT o.id FROM orders o WHERE o.id = ? AND " + DUE + " FOR UPDATE OF o SKIP LOCKED", orderId, now,
				now, reviewWindowSeconds(), slotMarginSeconds());
		if (claimed.isEmpty()) {
			return false;
		}
		closing.close(orderId, "PENDING_PAYMENT", "EXPIRED", Actor.SYSTEM, null, REASON);
		outbox.publish("order", orderId, EVENT_ORDER_EXPIRED, Map.of("orderId", orderId.toString()));
		return true;
	}

	int batchSize() {
		return properties.batchSize();
	}

	private long reviewWindowSeconds() {
		return properties.manualReviewWindow().toSeconds();
	}

	private long slotMarginSeconds() {
		return properties.manualReviewSlotMargin().toSeconds();
	}

}
