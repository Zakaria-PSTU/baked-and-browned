package com.bakedandbrowned.ordering.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bakedandbrowned.audit.service.AuditLog;
import com.bakedandbrowned.common.outbox.OutboxPublisher;
import com.bakedandbrowned.common.web.ApiException;
import com.bakedandbrowned.common.web.ApiExceptionHandler;
import com.bakedandbrowned.inventory.service.StockReservations;
import com.bakedandbrowned.ordering.dto.AdminOrderDtos.AdminOrderStatusEvent;
import com.bakedandbrowned.ordering.dto.AdminOrderDtos.OrderStatus;
import com.bakedandbrowned.ordering.dto.OrderDtos.OrderSummary;
import com.bakedandbrowned.ordering.service.OrderClosing.Actor;
import com.bakedandbrowned.ordering.service.OrderClosing.CurrentPayment;

/**
 * ADMIN order operations (architecture, Order state machine), each audited and recorded in the
 * status history:
 * <ul>
 * <li>Fulfilment, one step at a time: CONFIRMED → PREPARING (ingredients consumed once) → READY →
 * OUT_FOR_DELIVERY (dispatched notice) → DELIVERED.</li>
 * <li>Cancellation with a reason, from PENDING_PAYMENT, CONFIRMED, PREPARING or READY, at any time:
 * unconsumed holds are released once, consumed stock is not returned, and a succeeded payment gets
 * a refund request.</li>
 * </ul>
 * Never PENDING_PAYMENT → CONFIRMED (payment verification) or anything out of EXPIRED
 * (reconciliation). The order row is locked first, as for every other order change.
 */
@Service
public class OrderAdminService {

	public static final String INVALID_TRANSITION = "INVALID_TRANSITION";

	private static final Map<String, String> NEXT_STEP = Map.of("CONFIRMED", "PREPARING", "PREPARING", "READY",
			"READY", "OUT_FOR_DELIVERY", "OUT_FOR_DELIVERY", "DELIVERED");

	private static final Set<String> CANCELLABLE = Set.of("PENDING_PAYMENT", "CONFIRMED", "PREPARING", "READY");

	private final JdbcTemplate jdbc;

	private final OrderClosing closing;

	private final StockReservations stock;

	private final OrderQueryService queries;

	private final OutboxPublisher outbox;

	private final AuditLog audit;

	public OrderAdminService(JdbcTemplate jdbc, OrderClosing closing, StockReservations stock,
			OrderQueryService queries, OutboxPublisher outbox, AuditLog audit) {
		this.jdbc = jdbc;
		this.closing = closing;
		this.stock = stock;
		this.queries = queries;
		this.outbox = outbox;
		this.audit = audit;
	}

	/** The account that placed the order. */
	public record Customer(UUID userId, String name, String phone) {
	}

	@Transactional(readOnly = true)
	public Customer customer(UUID orderId) {
		return jdbc.query("""
				SELECT u.id, u.full_name, u.phone FROM orders o JOIN users u ON u.id = o.user_id WHERE o.id = ?""",
				(rs, row) -> new Customer(rs.getObject("id", UUID.class), rs.getString("full_name"),
						rs.getString("phone")),
				orderId).stream().findFirst().orElseThrow(OrderAdminService::notFound);
	}

	/** Full history with actors and reasons, oldest first. */
	@Transactional(readOnly = true)
	public List<AdminOrderStatusEvent> history(UUID orderId) {
		Integer exists = jdbc.queryForObject("SELECT count(*) FROM orders WHERE id = ?", Integer.class, orderId);
		if (exists == null || exists == 0) {
			throw notFound();
		}
		return jdbc.query("""
				SELECT from_status::text AS from_status, to_status::text AS to_status, actor_type::text AS actor_type,
				       actor_user_id, reason, created_at
				FROM order_status_history WHERE order_id = ? ORDER BY created_at, id""",
				(rs, row) -> new AdminOrderStatusEvent(rs.getString("from_status"), rs.getString("to_status"),
						rs.getString("actor_type"), rs.getObject("actor_user_id", UUID.class), rs.getString("reason"),
						rs.getTimestamp("created_at").toInstant()),
				orderId);
	}

	/**
	 * @throws ApiException 404 for an unknown order; 409 {@link #INVALID_TRANSITION}; 422 when a
	 * cancellation has no reason
	 */
	@Transactional
	public OrderSummary changeStatus(UUID orderId, UUID adminId, OrderStatus target, @Nullable String reason) {
		String note = reason == null || reason.isBlank() ? null : reason.strip();
		if (target == OrderStatus.CANCELLED && note == null) {
			throw ApiException.unprocessable(ApiExceptionHandler.VALIDATION_FAILED,
					"reason: required when cancelling an order");
		}
		List<String> rows = jdbc.queryForList("SELECT status::text FROM orders WHERE id = ? FOR UPDATE", String.class,
				orderId);
		if (rows.isEmpty()) {
			throw notFound();
		}
		String from = rows.get(0);
		String to = target.name();

		if (target == OrderStatus.CANCELLED) {
			if (!CANCELLABLE.contains(from)) {
				throw invalid(from, to);
			}
			CurrentPayment payment = closing.close(orderId, from, to, Actor.ADMIN, adminId, note);
			closing.requestRefundIfPaid(orderId, payment, Actor.ADMIN, adminId, note);
			outbox.publish(OrderEvents.AGGREGATE, orderId, OrderEvents.CANCELLED, Map.of("orderId", orderId.toString()));
		}
		else {
			if (!to.equals(NEXT_STEP.get(from))) {
				throw invalid(from, to);
			}
			jdbc.update("UPDATE orders SET status = ?::order_status WHERE id = ?", to, orderId);
			jdbc.update("""
					INSERT INTO order_status_history (order_id, from_status, to_status, actor_type, actor_user_id, reason)
					VALUES (?, ?::order_status, ?::order_status, 'ADMIN', ?, ?)""", orderId, from, to, adminId, note);
			if (target == OrderStatus.PREPARING) {
				stock.consume(orderId, adminId);
			}
			if (target == OrderStatus.OUT_FOR_DELIVERY) {
				outbox.publish(OrderEvents.AGGREGATE, orderId, OrderEvents.DISPATCHED,
						Map.of("orderId", orderId.toString()));
			}
		}
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("fromStatus", from);
		details.put("toStatus", to);
		audit.admin(adminId, "order.status_changed", "order", orderId, note, details);
		return queries.summary(orderId);
	}

	private static ApiException invalid(String from, String to) {
		String hint = switch (from) {
			case "PENDING_PAYMENT" -> " Confirm an unpaid order by verifying its payment.";
			case "EXPIRED" -> " Expired orders are resolved through payment reconciliation.";
			default -> "";
		};
		return ApiException.conflict(INVALID_TRANSITION,
				"An order cannot move from " + from + " to " + to + "." + hint);
	}

	private static ApiException notFound() {
		return ApiException.notFound(OrderQueryService.ORDER_NOT_FOUND, "Order not found.");
	}

}
