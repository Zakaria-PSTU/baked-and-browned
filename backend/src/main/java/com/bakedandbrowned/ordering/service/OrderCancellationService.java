package com.bakedandbrowned.ordering.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bakedandbrowned.common.outbox.OutboxPublisher;
import com.bakedandbrowned.common.web.ApiException;
import com.bakedandbrowned.ordering.dto.OrderDtos.OrderDetail;
import com.bakedandbrowned.ordering.service.OrderClosing.Actor;
import com.bakedandbrowned.ordering.service.OrderClosing.CurrentPayment;

/**
 * Customer cancellation (ADR-0009): own order, status PENDING_PAYMENT or CONFIRMED, and strictly
 * before the snapshotted cancellation deadline. Holds are released once; a payment that already
 * succeeded is not refunded automatically but gets a refund request for the ADMIN refund workflow.
 */
@Service
public class OrderCancellationService {

	public static final String INVALID_STATE = "INVALID_STATE";

	public static final String CANCELLATION_WINDOW_CLOSED = "CANCELLATION_WINDOW_CLOSED";

	static final String DEFAULT_REFUND_REASON = "Order cancelled by the customer.";

	private final JdbcTemplate jdbc;

	private final OrderClosing closing;

	private final OrderQueryService queries;

	private final OutboxPublisher outbox;

	private final Clock clock;

	public OrderCancellationService(JdbcTemplate jdbc, OrderClosing closing, OrderQueryService queries,
			OutboxPublisher outbox, Clock clock) {
		this.jdbc = jdbc;
		this.closing = closing;
		this.queries = queries;
		this.outbox = outbox;
		this.clock = clock;
	}

	/**
	 * @param reason optional customer note (at most 500 characters, validated at the API boundary)
	 * @throws ApiException 404 {@link OrderQueryService#ORDER_NOT_FOUND} for a missing order or
	 * someone else's; 409 {@link #INVALID_STATE} or {@link #CANCELLATION_WINDOW_CLOSED}
	 */
	@Transactional
	public OrderDetail cancelByCustomer(UUID orderId, UUID customerId, @Nullable String reason) {
		// The order row lock serializes this with expiry, payment decisions and repeated requests.
		Instant now = clock.instant();
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT user_id, status::text AS status, ? < cancellation_deadline AS window_open
				FROM orders WHERE id = ? FOR UPDATE""", Timestamp.from(now), orderId);
		if (rows.isEmpty() || !customerId.equals(rows.get(0).get("user_id"))) {
			throw ApiException.notFound(OrderQueryService.ORDER_NOT_FOUND, "Order not found.");
		}
		Map<String, Object> order = rows.get(0);
		String status = (String) order.get("status");
		if (!"PENDING_PAYMENT".equals(status) && !"CONFIRMED".equals(status)) {
			throw ApiException.conflict(INVALID_STATE, "This order can no longer be cancelled.");
		}
		if (!Boolean.TRUE.equals(order.get("window_open"))) {
			throw ApiException.conflict(CANCELLATION_WINDOW_CLOSED,
					"The cancellation window for this order has closed. Please contact us for help.");
		}

		String note = reason == null || reason.isBlank() ? null : reason.strip();
		CurrentPayment payment = closing.close(orderId, status, "CANCELLED", Actor.CUSTOMER, customerId, note);
		if ("SUCCEEDED".equals(payment.status()) && payment.amount().signum() > 0) {
			jdbc.update("""
					INSERT INTO refunds (payment_id, order_id, amount, request_reason, requested_by_type, requested_by)
					VALUES (?, ?, ?, ?, 'CUSTOMER', ?)
					ON CONFLICT DO NOTHING""", payment.id(), orderId, payment.amount(),
					note == null ? DEFAULT_REFUND_REASON : note, customerId);
		}
		outbox.publish(OrderEvents.AGGREGATE, orderId, OrderEvents.CANCELLED, Map.of("orderId", orderId.toString()));
		return queries.detail(orderId, customerId, false);
	}

}
