package com.bakedandbrowned.ordering.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.bakedandbrowned.common.outbox.OutboxPublisher;
import com.bakedandbrowned.inventory.service.StockReservations;
import com.bakedandbrowned.ordering.service.OrderClosing.Actor;

/**
 * The ordering side of payment decisions, for the payment module. Every method runs in the
 * caller's transaction ({@code MANDATORY}); {@link #lock} must come first, so payment decisions
 * serialize with cancellation and hold expiry on the order row.
 */
@Service
public class OrderPaymentOutcomes {

	private final JdbcTemplate jdbc;

	private final OrderClosing closing;

	private final StockReservations stock;

	private final OutboxPublisher outbox;

	public OrderPaymentOutcomes(JdbcTemplate jdbc, OrderClosing closing, StockReservations stock,
			OutboxPublisher outbox) {
		this.jdbc = jdbc;
		this.closing = closing;
		this.stock = stock;
		this.outbox = outbox;
	}

	/** An order as the payment module needs to see it. */
	public record PaymentOrder(UUID id, UUID userId, String orderNumber, String status, String paymentMethod,
			boolean holdOpen) {
	}

	/**
	 * Locks the order row {@code FOR UPDATE} (waiting for a concurrent cancellation or expiry to
	 * finish) and returns its current state; {@code holdOpen} is whether its payment hold
	 * (if any) is still running at {@code now}.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Optional<PaymentOrder> lock(UUID orderId, Instant now) {
		List<PaymentOrder> rows = jdbc.query("""
				SELECT id, user_id, order_number, status::text AS status, payment_method::text AS method,
				       (expires_at IS NULL OR expires_at > ?) AS hold_open
				FROM orders WHERE id = ? FOR UPDATE""",
				(rs, row) -> new PaymentOrder(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
						rs.getString("order_number"), rs.getString("status"), rs.getString("method"),
						rs.getBoolean("hold_open")),
				Timestamp.from(now), orderId);
		return rows.stream().findFirst();
	}

	/** The order's delivery contact snapshot and the customer's account email, for a hosted checkout. */
	public record CheckoutContact(String name, String phone, @Nullable String email, String address, String area) {

		@Override
		public String toString() {
			return "CheckoutContact[redacted]";
		}

	}

	@Transactional(propagation = Propagation.MANDATORY)
	public CheckoutContact checkoutContact(UUID orderId) {
		return jdbc.queryForObject("""
				SELECT o.customer_name, o.customer_phone, u.email, o.delivery_address, o.delivery_area
				FROM orders o JOIN users u ON u.id = o.user_id WHERE o.id = ?""",
				(rs, row) -> new CheckoutContact(rs.getString("customer_name"), rs.getString("customer_phone"),
						rs.getString("email"), rs.getString("delivery_address"), rs.getString("delivery_area")),
				orderId);
	}

	/**
	 * Payment verified: PENDING_PAYMENT → CONFIRMED, and the slot and stock holds become
	 * confirmed reservations.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void confirmPaid(UUID orderId, UUID adminId) {
		confirm(orderId, "ADMIN", adminId);
	}

	/** As {@link #confirmPaid}, for a payment the gateway verified server to server (actor GATEWAY). */
	@Transactional(propagation = Propagation.MANDATORY)
	public void confirmPaidByGateway(UUID orderId) {
		confirm(orderId, "GATEWAY", null);
	}

	private void confirm(UUID orderId, String actorType, @Nullable UUID actorUserId) {
		requireStatus(orderId, "PENDING_PAYMENT");
		jdbc.update("UPDATE orders SET status = 'CONFIRMED' WHERE id = ?", orderId);
		jdbc.update("""
				INSERT INTO order_status_history (order_id, from_status, to_status, actor_type, actor_user_id)
				VALUES (?, 'PENDING_PAYMENT', 'CONFIRMED', ?::actor_type, ?)""", orderId, actorType, actorUserId);
		jdbc.update("UPDATE slot_reservations SET status = 'CONFIRMED' WHERE order_id = ? AND status = 'HELD'",
				orderId);
		stock.confirm(orderId);
		outbox.publish(OrderEvents.AGGREGATE, orderId, OrderEvents.CONFIRMED, Map.of("orderId", orderId.toString()));
	}

	/**
	 * Payment rejected: PENDING_PAYMENT → CANCELLED with the ADMIN's note, holds released once. The
	 * caller records the payment's own outcome afterwards.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void cancelUnpaid(UUID orderId, UUID adminId, String note) {
		requireStatus(orderId, "PENDING_PAYMENT");
		closing.close(orderId, "PENDING_PAYMENT", "CANCELLED", Actor.ADMIN, adminId, note);
		outbox.publish(OrderEvents.AGGREGATE, orderId, OrderEvents.CANCELLED,
				Map.of("orderId", orderId.toString()));
	}

	private void requireStatus(UUID orderId, String expected) {
		String status = jdbc.queryForObject("SELECT status::text FROM orders WHERE id = ?", String.class, orderId);
		if (!expected.equals(status)) {
			throw new IllegalStateException("Order " + orderId + " is " + status + ", expected " + expected);
		}
	}

}
