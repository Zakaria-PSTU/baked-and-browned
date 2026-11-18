package com.bakedandbrowned.ordering.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.bakedandbrowned.inventory.service.StockReservations;
import com.bakedandbrowned.scheduling.service.SlotCapacity;

/**
 * Moves an order that is still before preparation to a terminal state (CANCELLED or EXPIRED) and
 * releases what it holds (business rules, Delivery scheduling and Ingredient inventory: "release
 * once only").
 * <p>
 * The caller has already locked the order row {@code FOR UPDATE} and checked that the transition
 * is allowed; that lock serializes every closing of the same order (cancellation, expiry, payment
 * decisions), so a reservation can only be released once. Further locks follow the checkout order:
 * slot, then ingredients by id.
 */
@Component
class OrderClosing {

	private final JdbcTemplate jdbc;

	private final SlotCapacity slotCapacity;

	private final StockReservations stock;

	OrderClosing(JdbcTemplate jdbc, SlotCapacity slotCapacity, StockReservations stock) {
		this.jdbc = jdbc;
		this.slotCapacity = slotCapacity;
		this.stock = stock;
	}

	/** Who closes the order; matches the {@code actor_type} enum. */
	enum Actor {

		CUSTOMER, ADMIN, SYSTEM

	}

	/**
	 * Writes the status change and its history row, releases slot and stock holds, and cancels a
	 * still-pending payment.
	 * @return the order's current payment after the change
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	CurrentPayment close(UUID orderId, String fromStatus, String toStatus, Actor actor, @Nullable UUID actorUserId,
			@Nullable String reason) {
		jdbc.update("UPDATE orders SET status = ?::order_status WHERE id = ?", toStatus, orderId);
		jdbc.update("""
				INSERT INTO order_status_history (order_id, from_status, to_status, actor_type, actor_user_id, reason)
				VALUES (?, ?::order_status, ?::order_status, ?::actor_type, ?, ?)""", orderId, fromStatus, toStatus,
				actor.name(), actorUserId, reason);
		releaseSlot(orderId);
		stock.release(orderId);

		CurrentPayment payment = jdbc.queryForObject("""
				SELECT id, status::text AS status, amount FROM payments
				WHERE order_id = ? AND superseded_at IS NULL FOR UPDATE""",
				(rs, row) -> new CurrentPayment(rs.getObject("id", UUID.class), rs.getString("status"),
						rs.getBigDecimal("amount")),
				orderId);
		if (payment == null) {
			throw new IllegalStateException("Order " + orderId + " has no current payment");
		}
		if (!"PENDING".equals(payment.status())) {
			return payment;
		}
		jdbc.update("UPDATE payments SET status = 'CANCELLED', failure_reason = ? WHERE id = ?",
				"Order " + toStatus.toLowerCase(java.util.Locale.ROOT), payment.id());
		return new CurrentPayment(payment.id(), "CANCELLED", payment.amount());
	}

	/** Returns the slot units if the reservation is still live; a no-op once released or consumed. */
	private void releaseSlot(UUID orderId) {
		List<Map<String, Object>> live = jdbc.queryForList("""
				SELECT id, slot_id, reserved_units FROM slot_reservations
				WHERE order_id = ? AND status IN ('HELD', 'CONFIRMED') FOR UPDATE""", orderId);
		for (Map<String, Object> r : live) {
			slotCapacity.release((UUID) r.get("slot_id"), (Integer) r.get("reserved_units"));
			jdbc.update("UPDATE slot_reservations SET status = 'RELEASED', released_at = now() WHERE id = ?",
					r.get("id"));
		}
	}

	/**
	 * A cancelled order whose payment already succeeded is not refunded automatically: this opens a
	 * full refund request for the ADMIN refund workflow (at most one active request per payment).
	 * @return whether a request was opened
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	boolean requestRefundIfPaid(UUID orderId, CurrentPayment payment, Actor actor, UUID actorUserId, String reason) {
		if (!"SUCCEEDED".equals(payment.status()) || payment.amount().signum() <= 0) {
			return false;
		}
		return jdbc.update("""
				INSERT INTO refunds (payment_id, order_id, amount, request_reason, requested_by_type, requested_by)
				VALUES (?, ?, ?, ?, ?::actor_type, ?)
				ON CONFLICT DO NOTHING""", payment.id(), orderId, payment.amount(), reason, actor.name(),
				actorUserId) == 1;
	}

	record CurrentPayment(UUID id, String status, java.math.BigDecimal amount) {
	}

}
