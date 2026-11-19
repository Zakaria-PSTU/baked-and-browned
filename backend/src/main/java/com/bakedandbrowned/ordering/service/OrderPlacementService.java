package com.bakedandbrowned.ordering.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.bakedandbrowned.common.web.ApiException;
import com.bakedandbrowned.ordering.dto.OrderDtos.OrderDetail;
import com.bakedandbrowned.ordering.dto.OrderDtos.PaymentMethod;
import com.bakedandbrowned.ordering.dto.OrderDtos.PlaceOrderRequest;
import com.bakedandbrowned.payment.gateway.PaymentGateways;
import com.bakedandbrowned.payment.service.ManualPaymentProperties;

import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /orders} (architecture, Core checkout consistency):
 * <ol>
 * <li>Idempotency first: an existing order for {@code (user, idempotencyKey)} with the same request
 * hash is returned as is (201, nothing new), with a different hash is 422
 * {@code IDEMPOTENCY_KEY_REUSED}. Replays never re-price, so a later price change cannot break them.</li>
 * <li>An ONLINE order needs an active payment gateway (409 {@code ONLINE_PAYMENT_UNAVAILABLE}) and a MANUAL
 * order configured receiving accounts (409 {@code MANUAL_PAYMENT_UNAVAILABLE}), so no
 * stock or slot is held for an order that could never be paid.</li>
 * <li>Re-price and validate the cart against current data ({@link CartPricer}, 422s).</li>
 * <li>Write order, snapshots and reservations in one transaction ({@link OrderWriter}, 409 when the
 * last capacity or stock was taken first).</li>
 * <li>A concurrent duplicate that loses the unique {@code (user_id, idempotency_key)} race recovers the
 * winner's committed order the same way as step 1.</li>
 * </ol>
 * Not itself transactional: pricing reads and the write transaction stay short and separate.
 */
@Service
public class OrderPlacementService {

	public static final String IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED";

	/** Same code as {@code initiatePayment} uses when the gateway is gone by the time the customer pays. */
	public static final String ONLINE_PAYMENT_UNAVAILABLE = "ONLINE_PAYMENT_UNAVAILABLE";

	/** No receiving accounts are configured (ADR-0022), so a transfer could not be sent anywhere. */
	public static final String MANUAL_PAYMENT_UNAVAILABLE = "MANUAL_PAYMENT_UNAVAILABLE";

	private final CartPricer pricer;

	private final OrderWriter writer;

	private final OrderQueryService queries;

	private final JdbcTemplate jdbc;

	private final JsonMapper json;

	private final Clock clock;

	private final PaymentGateways gateways;

	private final ManualPaymentProperties manual;

	OrderPlacementService(CartPricer pricer, OrderWriter writer, OrderQueryService queries, JdbcTemplate jdbc,
			JsonMapper json, Clock clock, PaymentGateways gateways, ManualPaymentProperties manual) {
		this.pricer = pricer;
		this.writer = writer;
		this.queries = queries;
		this.jdbc = jdbc;
		this.json = json;
		this.clock = clock;
		this.gateways = gateways;
		this.manual = manual;
	}

	public OrderDetail place(UUID userId, PlaceOrderRequest request) {
		String hash = RequestHash.of(request, json);
		OrderDetail existing = replay(userId, request.idempotencyKey(), hash);
		if (existing != null) {
			return existing;
		}
		if (request.paymentMethod() == PaymentMethod.ONLINE && gateways.active().isEmpty()) {
			throw ApiException.conflict(ONLINE_PAYMENT_UNAVAILABLE,
					"Online payment is not available right now. Please choose another payment method.");
		}
		if (request.paymentMethod() == PaymentMethod.MANUAL && manual.accounts().isEmpty()) {
			throw ApiException.conflict(MANUAL_PAYMENT_UNAVAILABLE,
					"Payment by transfer is not available right now. Please choose another payment method.");
		}
		Instant now = clock.instant();
		PricedCart cart = pricer.price(request.cart(), now);
		UUID orderId;
		try {
			orderId = writer.write(userId, request, hash, cart, now);
		}
		catch (DuplicateKeyException ex) {
			OrderDetail winner = replay(userId, request.idempotencyKey(), hash);
			if (winner == null) {
				throw ex; // some other unique constraint: a genuine bug, not a duplicate request
			}
			return winner;
		}
		return queries.detail(orderId, userId, false);
	}

	/** The order already placed with this key, if any; 422 if it was placed from a different request. */
	private OrderDetail replay(UUID userId, UUID idempotencyKey, String hash) {
		List<Map<String, Object>> rows = jdbc.queryForList(
				"SELECT id, request_hash FROM orders WHERE user_id = ? AND idempotency_key = ?", userId, idempotencyKey);
		if (rows.isEmpty()) {
			return null;
		}
		if (!hash.equals(rows.get(0).get("request_hash"))) {
			throw ApiException.unprocessable(IDEMPOTENCY_KEY_REUSED,
					"This idempotency key was already used for a different order.");
		}
		return queries.detail((UUID) rows.get(0).get("id"), userId, false);
	}

}
