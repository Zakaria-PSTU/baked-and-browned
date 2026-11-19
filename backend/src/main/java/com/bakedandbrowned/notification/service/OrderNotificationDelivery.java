package com.bakedandbrowned.notification.service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.bakedandbrowned.common.outbox.OutboxMessage;
import com.bakedandbrowned.notification.service.OrderNotices.Kind;
import com.bakedandbrowned.notification.service.OrderNotices.OrderFacts;
import com.bakedandbrowned.notification.sms.SmsSender;
import com.bakedandbrowned.notification.sms.SmsSender.SmsDeliveryException;
import com.bakedandbrowned.notification.sms.SmsSender.SmsMessage;

/**
 * Sends the SMS notice for one order event to the account holder's phone (not the delivery
 * contact, who may be a gift recipient).
 * <p>
 * Idempotent per outbox event: the first attempt records the notice (recipient and text fixed from
 * then on) under {@code UNIQUE (outbox_event_id, channel)}; a notice already SENT is never sent
 * again. Each statement autocommits and no lock is held while the provider is called.
 */
// Not in the provision-admin command (ADR-0024): it sends no notices, so it needs no SmsSender.
@Profile("!provision-admin")
@Service
public class OrderNotificationDelivery {

	private static final Logger log = LoggerFactory.getLogger(OrderNotificationDelivery.class);

	private final JdbcTemplate jdbc;

	private final SmsSender sms;

	public OrderNotificationDelivery(JdbcTemplate jdbc, SmsSender sms) {
		this.jdbc = jdbc;
		this.sms = sms;
	}

	/**
	 * @throws SmsDeliveryException if the provider failed; the outbox retries the event later and
	 * the same notice is resent
	 */
	public void deliver(OutboxMessage event, Kind kind) throws SmsDeliveryException {
		Map<String, Object> notice = existing(event).orElseGet(() -> {
			record(event, kind);
			return existing(event).orElseThrow();
		});
		if ("SENT".equals(notice.get("status"))) {
			log.debug("Notice for {} already sent", event);
			return;
		}
		UUID noticeId = (UUID) notice.get("id");
		jdbc.update("UPDATE notifications SET attempts = attempts + 1 WHERE id = ?", noticeId);
		String providerMessageId = sms
			.send(new SmsMessage(noticeId, (String) notice.get("recipient"), (String) notice.get("body")));
		jdbc.update("""
				UPDATE notifications SET status = 'SENT', provider = ?, provider_message_id = ?, sent_at = now()
				WHERE id = ? AND status = 'PENDING'""", sms.provider(), providerMessageId, noticeId);
	}

	private java.util.Optional<Map<String, Object>> existing(OutboxMessage event) {
		return jdbc.queryForList("""
				SELECT id, status::text AS status, recipient, body FROM notifications
				WHERE outbox_event_id = ? AND channel = 'SMS'""", event.id()).stream().findFirst();
	}

	/** First attempt: fixes recipient and text. A concurrent first attempt for the same event loses quietly. */
	private void record(OutboxMessage event, Kind kind) {
		// Order events carry the order id; refund events the refund id, which names its order and amount.
		UUID orderId = event.aggregateId();
		BigDecimal refundAmount = null;
		if (kind == Kind.REFUND_COMPLETED) {
			Map<String, Object> refund = jdbc.queryForMap("SELECT order_id, amount FROM refunds WHERE id = ?",
					event.aggregateId());
			orderId = (UUID) refund.get("order_id");
			refundAmount = (BigDecimal) refund.get("amount");
		}
		Map<String, Object> order = jdbc.queryForMap("""
				SELECT o.order_number, o.user_id, o.payment_method::text AS payment_method, o.total_amount,
				       o.status::text AS status, o.expires_at, s.start_at, s.end_at, u.phone,
				       (SELECT r.amount FROM refunds r WHERE r.order_id = o.id AND r.status = 'REQUESTED'
				        ORDER BY r.created_at DESC LIMIT 1) AS refund_amount
				FROM orders o
				JOIN delivery_slots s ON s.id = o.delivery_slot_id
				JOIN users u ON u.id = o.user_id
				WHERE o.id = ?""", orderId);
		String text = OrderNotices.text(kind, facts(kind, order, refundAmount));
		jdbc.update("""
				INSERT INTO notifications (outbox_event_id, order_id, user_id, kind, channel, recipient, body)
				VALUES (?, ?, ?, ?, 'SMS', ?, ?)
				ON CONFLICT ON CONSTRAINT uq_notification_event_channel DO NOTHING""", event.id(), orderId,
				order.get("user_id"), kind.name(), order.get("phone"), text);
	}

	private static OrderFacts facts(Kind kind, Map<String, Object> order, @Nullable BigDecimal refundAmount) {
		// The payment deadline only matters while the order is still waiting for it.
		Timestamp expires = (Timestamp) order.get("expires_at");
		boolean awaitingPayment = kind == Kind.ORDER_RECEIVED && "PENDING_PAYMENT".equals(order.get("status"))
				&& expires != null;
		return new OrderFacts((String) order.get("order_number"), (String) order.get("payment_method"),
				(BigDecimal) order.get("total_amount"), ((Timestamp) order.get("start_at")).toInstant(),
				((Timestamp) order.get("end_at")).toInstant(), awaitingPayment ? expires.toInstant() : null,
				refundAmount != null ? refundAmount : (BigDecimal) order.get("refund_amount"));
	}

}
