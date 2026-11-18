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
import com.bakedandbrowned.ordering.dto.OrderDtos.PlaceOrderRequest;

import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /orders} (architecture, Core checkout consistency):
 * <ol>
 * <li>Idempotency first: an existing order for {@code (user, idempotencyKey)} with the same request
 * hash is returned as is (201, nothing new), with a different hash is 422
 * {@code IDEMPOTENCY_KEY_REUSED}. Replays never re-price, so a later price change cannot break them.</li>
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

	private final CartPricer pricer;

	private final OrderWriter writer;

	private final OrderQueryService queries;

	private final JdbcTemplate jdbc;

	private final JsonMapper json;

	private final Clock clock;

	OrderPlacementService(CartPricer pricer, OrderWriter writer, OrderQueryService queries, JdbcTemplate jdbc,
			JsonMapper json, Clock clock) {
		this.pricer = pricer;
		this.writer = writer;
		this.queries = queries;
		this.jdbc = jdbc;
		this.json = json;
		this.clock = clock;
	}

	public OrderDetail place(UUID userId, PlaceOrderRequest request) {
		String hash = RequestHash.of(request, json);
		OrderDetail existing = replay(userId, request.idempotencyKey(), hash);
		if (existing != null) {
			return existing;
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
