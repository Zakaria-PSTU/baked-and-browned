package com.bakedandbrowned.inventory.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.bakedandbrowned.common.value.Quantity;
import com.bakedandbrowned.common.web.ApiException;

/**
 * Ingredient reservations for orders (business rules, Ingredient inventory). All operations run in
 * the caller's order transaction ({@code MANDATORY}).
 * <ul>
 * <li>{@link #reserve}: guarded {@code UPDATE}s in ascending ingredient-id order (deterministic
 * lock order across concurrent checkouts, so they cannot deadlock); any shortfall is 409 and the
 * caller's transaction rolls back every earlier increment.</li>
 * <li>{@link #confirm}, {@link #release}, {@link #consume}: act only on reservations still HELD or
 * CONFIRMED, locked {@code FOR UPDATE}, so each happens at most once no matter how often or how
 * concurrently it is called.</li>
 * </ul>
 */
@Service
public class StockReservations {

	public static final String INSUFFICIENT_STOCK = "INSUFFICIENT_STOCK";

	private final JdbcTemplate jdbc;

	public StockReservations(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Whether {@code needed} is available right now (on hand minus reserved), without reserving.
	 * Informative only: a concurrent checkout may take the stock before this caller reserves it.
	 */
	@Transactional(readOnly = true)
	public boolean available(Map<UUID, Quantity> needed) {
		if (needed.isEmpty()) {
			return true;
		}
		Map<UUID, BigDecimal> free = new java.util.HashMap<>();
		jdbc.query(con -> {
			var ps = con.prepareStatement("SELECT id, on_hand - reserved AS free FROM ingredients WHERE id = ANY (?)");
			ps.setArray(1, con.createArrayOf("uuid", needed.keySet().toArray()));
			return ps;
		}, rs -> {
			free.put(rs.getObject("id", UUID.class), rs.getBigDecimal("free"));
		});
		return needed.entrySet()
			.stream()
			.allMatch(e -> free.containsKey(e.getKey()) && free.get(e.getKey()).compareTo(e.getValue().value()) >= 0);
	}

	/**
	 * Reserves {@code needed} (ingredient id to quantity, ascending id order) for {@code orderId}.
	 * @throws ApiException 409 {@link #INSUFFICIENT_STOCK} if any ingredient lacks available stock
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void reserve(UUID orderId, SortedMap<UUID, Quantity> needed) {
		for (Map.Entry<UUID, Quantity> line : needed.entrySet()) {
			BigDecimal quantity = line.getValue().value();
			int updated = jdbc.update("""
					UPDATE ingredients SET reserved = reserved + ?
					WHERE id = ? AND on_hand - reserved >= ?""", quantity, line.getKey(), quantity);
			if (updated != 1) {
				throw ApiException.conflict(INSUFFICIENT_STOCK,
						"Some items in your order are no longer available in that quantity.");
			}
			jdbc.update("INSERT INTO inventory_reservations (order_id, ingredient_id, quantity) VALUES (?, ?, ?)", orderId,
					line.getKey(), quantity);
		}
	}

	/** Payment confirmed: HELD reservations become CONFIRMED (stock stays reserved). */
	@Transactional(propagation = Propagation.MANDATORY)
	public int confirm(UUID orderId) {
		return jdbc.update("UPDATE inventory_reservations SET status = 'CONFIRMED' WHERE order_id = ? AND status = 'HELD'",
				orderId);
	}

	/**
	 * Cancellation or expiry before preparation: returns the reserved quantities to availability and
	 * marks the reservations RELEASED. Returns how many were released (0 if already done).
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public int release(UUID orderId) {
		List<Map<String, Object>> live = lockLive(orderId);
		for (Map<String, Object> r : live) {
			jdbc.update("UPDATE ingredients SET reserved = reserved - ? WHERE id = ?", r.get("quantity"),
					r.get("ingredient_id"));
			jdbc.update("UPDATE inventory_reservations SET status = 'RELEASED', released_at = now() WHERE id = ?",
					r.get("id"));
		}
		return live.size();
	}

	/**
	 * The order enters PREPARING: deducts the reserved quantities from on-hand and reserved, marks
	 * the reservations CONSUMED and writes one CONSUMPTION movement per ingredient (also guarded by
	 * the unique index {@code uq_inventory_consumption_once}). Returns how many were consumed.
	 * @param actorUserId the ADMIN moving the order to PREPARING, or null for a system action
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public int consume(UUID orderId, @Nullable UUID actorUserId) {
		List<Map<String, Object>> live = lockLive(orderId);
		for (Map<String, Object> r : live) {
			BigDecimal quantity = (BigDecimal) r.get("quantity");
			BigDecimal onHandAfter = jdbc.queryForObject("""
					UPDATE ingredients SET on_hand = on_hand - ?, reserved = reserved - ?
					WHERE id = ? RETURNING on_hand""", BigDecimal.class, quantity, quantity, r.get("ingredient_id"));
			jdbc.update("UPDATE inventory_reservations SET status = 'CONSUMED', consumed_at = now() WHERE id = ?",
					r.get("id"));
			jdbc.update("""
					INSERT INTO inventory_movements
					  (ingredient_id, movement_type, quantity_delta, on_hand_after, order_id, actor_type, actor_user_id)
					VALUES (?, 'CONSUMPTION', ?, ?, ?, ?::actor_type, ?)""", r.get("ingredient_id"), quantity.negate(),
					onHandAfter, orderId, actorUserId == null ? "SYSTEM" : "ADMIN", actorUserId);
		}
		return live.size();
	}

	/**
	 * A released order is reinstated (ADMIN resolving a late payment): its RELEASED reservations are
	 * reserved again, in ingredient-id order, and become CONFIRMED. All or nothing: the caller's
	 * transaction rolls back on the 409.
	 * @throws ApiException 409 {@link #INSUFFICIENT_STOCK} if any ingredient lacks available stock now
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public int reinstate(UUID orderId) {
		List<Map<String, Object>> released = jdbc.queryForList("""
				SELECT id, ingredient_id, quantity FROM inventory_reservations
				WHERE order_id = ? AND status = 'RELEASED'
				ORDER BY ingredient_id FOR UPDATE""", orderId);
		for (Map<String, Object> r : released) {
			BigDecimal quantity = (BigDecimal) r.get("quantity");
			int updated = jdbc.update("""
					UPDATE ingredients SET reserved = reserved + ?
					WHERE id = ? AND on_hand - reserved >= ?""", quantity, r.get("ingredient_id"), quantity);
			if (updated != 1) {
				throw ApiException.conflict(INSUFFICIENT_STOCK,
						"There is no longer enough stock to reinstate this order.");
			}
			jdbc.update("UPDATE inventory_reservations SET status = 'CONFIRMED', released_at = NULL WHERE id = ?",
					r.get("id"));
		}
		return released.size();
	}

	/** Still-live reservations of the order, locked, in ingredient-id order (consistent lock order). */
	private List<Map<String, Object>> lockLive(UUID orderId) {
		return jdbc.queryForList("""
				SELECT id, ingredient_id, quantity FROM inventory_reservations
				WHERE order_id = ? AND status IN ('HELD', 'CONFIRMED')
				ORDER BY ingredient_id FOR UPDATE""", orderId);
	}

}
