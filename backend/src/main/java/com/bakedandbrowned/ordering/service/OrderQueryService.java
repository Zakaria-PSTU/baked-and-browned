package com.bakedandbrowned.ordering.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bakedandbrowned.common.value.Money;
import com.bakedandbrowned.common.web.ApiException;
import com.bakedandbrowned.ordering.dto.OrderDtos.CustomizationSnapshot;
import com.bakedandbrowned.ordering.dto.OrderDtos.OrderDeliveryResponse;
import com.bakedandbrowned.ordering.dto.OrderDtos.OrderDetail;
import com.bakedandbrowned.ordering.dto.OrderDtos.OrderLineResponse;
import com.bakedandbrowned.ordering.dto.OrderDtos.OrderSummary;
import com.bakedandbrowned.ordering.dto.OrderDtos.PaymentSummary;
import com.bakedandbrowned.ordering.dto.OrderDtos.StatusEvent;

/**
 * Customer-safe order views. A customer sees only their own orders; anyone else's is the same 404 as
 * a missing one (non-enumerating). ADMIN may read any order here (admin detail has its own API).
 */
@Service
@Transactional(readOnly = true)
public class OrderQueryService {

	public static final String ORDER_NOT_FOUND = "ORDER_NOT_FOUND";

	/** Upper bound on the customer's order list (the contract returns a plain array). */
	static final int MAX_LISTED = 200;

	/** Upper bound on the ADMIN order list (also a plain array in the contract). */
	static final int MAX_ADMIN_LISTED = 500;

	private static final String ORDER_COLUMNS = """
			o.id, o.order_number, o.status::text AS status, o.subtotal, o.delivery_fee, o.discount_amount,
			o.total_amount, o.currency, o.payment_method::text AS payment_method, o.created_at,
			(SELECT p.status::text FROM payments p WHERE p.order_id = o.id AND p.superseded_at IS NULL) AS payment_status""";

	private final JdbcTemplate jdbc;

	private final Clock clock;

	public OrderQueryService(JdbcTemplate jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	public List<OrderSummary> mine(UUID userId) {
		return jdbc.query("SELECT " + ORDER_COLUMNS + " FROM orders o WHERE o.user_id = ? ORDER BY o.created_at DESC, o.id DESC LIMIT ?",
				(rs, row) -> summary(rs), userId, MAX_LISTED);
	}

	/** ADMIN list: every customer's orders, newest first, optionally one status, at most {@link #MAX_ADMIN_LISTED}. */
	public List<OrderSummary> all(@Nullable String status) {
		return status == null
				? jdbc.query("SELECT " + ORDER_COLUMNS + " FROM orders o ORDER BY o.created_at DESC, o.id DESC LIMIT ?",
						(rs, row) -> summary(rs), MAX_ADMIN_LISTED)
				: jdbc.query("SELECT " + ORDER_COLUMNS
						+ " FROM orders o WHERE o.status = ?::order_status ORDER BY o.created_at DESC, o.id DESC LIMIT ?",
						(rs, row) -> summary(rs), status, MAX_ADMIN_LISTED);
	}

	/** One order's summary, for any viewer the caller has already authorized. */
	public OrderSummary summary(UUID orderId) {
		return jdbc.query("SELECT " + ORDER_COLUMNS + " FROM orders o WHERE o.id = ?", (rs, row) -> summary(rs), orderId)
			.stream()
			.findFirst()
			.orElseThrow(() -> ApiException.notFound(ORDER_NOT_FOUND, "Order not found."));
	}

	/** The detail of an order the viewer may see. */
	public OrderDetail detail(UUID orderId, UUID viewerId, boolean viewerIsAdmin) {
		return find(orderId, viewerId, viewerIsAdmin)
			.orElseThrow(() -> ApiException.notFound(ORDER_NOT_FOUND, "Order not found."));
	}

	/** Same visibility rules; empty when the viewer may not see the order or it does not exist. */
	public Optional<OrderDetail> find(UUID orderId, UUID viewerId, boolean viewerIsAdmin) {
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT o.user_id, o.order_number, o.status::text AS status, o.subtotal, o.delivery_fee,
				       o.discount_amount, o.total_amount, o.currency, o.payment_method::text AS payment_method,
				       o.created_at, o.updated_at, o.expires_at, o.cancellation_deadline, o.customer_name,
				       o.customer_phone, o.delivery_address, o.delivery_notes, o.delivery_area, z.code AS zone_code,
				       s.id AS slot_id, s.start_at, s.end_at
				FROM orders o
				JOIN delivery_zones z ON z.id = o.delivery_zone_id
				JOIN delivery_slots s ON s.id = o.delivery_slot_id
				WHERE o.id = ?""", orderId);
		if (rows.isEmpty()) {
			return Optional.empty();
		}
		Map<String, Object> o = rows.get(0);
		if (!viewerIsAdmin && !viewerId.equals(o.get("user_id"))) {
			return Optional.empty();
		}
		String status = (String) o.get("status");
		Instant deadline = instant(o.get("cancellation_deadline"));
		boolean cancellable = ("PENDING_PAYMENT".equals(status) || "CONFIRMED".equals(status))
				&& clock.instant().isBefore(deadline);
		PaymentSummary payment = payment(orderId, (String) o.get("payment_method"));
		return Optional.of(new OrderDetail(orderId, (String) o.get("order_number"), status, payment.status(),
				money(o.get("subtotal")), money(o.get("delivery_fee")), money(o.get("discount_amount")),
				money(o.get("total_amount")), ((String) o.get("currency")).strip(), (String) o.get("payment_method"),
				instant(o.get("created_at")), instant(o.get("updated_at")), items(orderId),
				new OrderDeliveryResponse((String) o.get("customer_name"), (String) o.get("customer_phone"),
						(String) o.get("zone_code"), (String) o.get("delivery_area"), (String) o.get("delivery_address"),
						(String) o.get("delivery_notes"), (UUID) o.get("slot_id"), instant(o.get("start_at")),
						instant(o.get("end_at"))),
				payment, history(orderId), o.get("expires_at") == null ? null : instant(o.get("expires_at")), deadline,
				cancellable));
	}

	private List<OrderLineResponse> items(UUID orderId) {
		Map<UUID, List<CustomizationSnapshot>> byItem = new LinkedHashMap<>();
		jdbc.query("""
				SELECT c.order_item_id, c.group_id, c.group_name, c.option_id, c.option_name, c.text_value, c.price_delta
				FROM order_item_customizations c JOIN order_items i ON i.id = c.order_item_id
				WHERE i.order_id = ? ORDER BY c.order_item_id, c.display_order, c.group_name, c.option_name, c.id""", rs -> {
			byItem.computeIfAbsent(rs.getObject("order_item_id", UUID.class), id -> new ArrayList<>())
				.add(new CustomizationSnapshot(rs.getObject("group_id", UUID.class), rs.getString("group_name"),
						rs.getObject("option_id", UUID.class), rs.getString("option_name"), rs.getString("text_value"),
						Money.of(rs.getBigDecimal("price_delta"))));
		}, orderId);
		return jdbc.query("""
				SELECT id, product_id, product_name, quantity, unit_base_price, unit_options_price, unit_price, line_total
				FROM order_items WHERE order_id = ? ORDER BY product_name, line_total, id""",
				(rs, row) -> new OrderLineResponse(rs.getObject("product_id", UUID.class), rs.getString("product_name"),
						rs.getInt("quantity"), Money.of(rs.getBigDecimal("unit_base_price")),
						Money.of(rs.getBigDecimal("unit_options_price")), Money.of(rs.getBigDecimal("unit_price")),
						Money.of(rs.getBigDecimal("line_total")),
						byItem.getOrDefault(rs.getObject("id", UUID.class), List.of())),
				orderId);
	}

	private PaymentSummary payment(UUID orderId, String method) {
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT p.status::text AS status,
				       (SELECT m.status::text FROM manual_payment_submissions m WHERE m.payment_id = p.id
				        ORDER BY m.created_at DESC LIMIT 1) AS manual_status,
				       (SELECT r.status::text FROM refunds r WHERE r.order_id = p.order_id
				        ORDER BY r.created_at DESC LIMIT 1) AS refund_status,
				       EXISTS (SELECT 1 FROM payment_reconciliation_cases c
				               WHERE c.order_id = p.order_id AND c.status = 'OPEN') AS under_review
				FROM payments p WHERE p.order_id = ? AND p.superseded_at IS NULL""", orderId);
		if (rows.isEmpty()) {
			throw new IllegalStateException("Order " + orderId + " has no current payment");
		}
		Map<String, Object> p = rows.get(0);
		return new PaymentSummary(method, (String) p.get("status"), (String) p.get("manual_status"),
				(String) p.get("refund_status"), Boolean.TRUE.equals(p.get("under_review")));
	}

	private List<StatusEvent> history(UUID orderId) {
		return jdbc.query(
				"SELECT to_status::text AS status, created_at FROM order_status_history WHERE order_id = ? ORDER BY created_at, id",
				(rs, row) -> new StatusEvent(rs.getString("status"), rs.getTimestamp("created_at").toInstant()), orderId);
	}

	private static OrderSummary summary(ResultSet rs) throws SQLException {
		return new OrderSummary(rs.getObject("id", UUID.class), rs.getString("order_number"), rs.getString("status"),
				rs.getString("payment_status"), Money.of(rs.getBigDecimal("subtotal")),
				Money.of(rs.getBigDecimal("delivery_fee")), Money.of(rs.getBigDecimal("discount_amount")),
				Money.of(rs.getBigDecimal("total_amount")), rs.getString("currency").strip(),
				rs.getString("payment_method"), rs.getTimestamp("created_at").toInstant());
	}

	private static Money money(@Nullable Object value) {
		return Money.of((java.math.BigDecimal) value);
	}

	private static Instant instant(@Nullable Object value) {
		if (value instanceof Timestamp ts) {
			return ts.toInstant();
		}
		if (value instanceof java.time.OffsetDateTime odt) {
			return odt.toInstant();
		}
		throw new IllegalStateException("Not a timestamp: " + value);
	}

}
