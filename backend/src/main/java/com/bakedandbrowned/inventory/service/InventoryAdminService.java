package com.bakedandbrowned.inventory.service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bakedandbrowned.audit.service.AuditLog;
import com.bakedandbrowned.common.value.Quantity;
import com.bakedandbrowned.common.web.ApiException;
import com.bakedandbrowned.common.web.PageResponse;
import com.bakedandbrowned.inventory.dto.InventoryDtos.IngredientCreate;
import com.bakedandbrowned.inventory.dto.InventoryDtos.IngredientResponse;
import com.bakedandbrowned.inventory.dto.InventoryDtos.InventoryAdjustment;
import com.bakedandbrowned.inventory.dto.InventoryDtos.MovementResponse;
import com.bakedandbrowned.inventory.entity.Ingredient;
import com.bakedandbrowned.inventory.repository.IngredientRepository;

import jakarta.persistence.EntityManager;

/**
 * Admin stock management (business rules, Ingredient inventory). Every on-hand change writes an
 * {@code inventory_movements} row (append-only ledger) and an audit event, in the same transaction.
 * On-hand can never go below what is reserved (409).
 */
@Service
public class InventoryAdminService {

	public static final String INGREDIENT_NOT_FOUND = "INGREDIENT_NOT_FOUND";

	public static final String INGREDIENT_EXISTS = "INGREDIENT_EXISTS";

	public static final String BELOW_RESERVED = "BELOW_RESERVED";

	private final IngredientRepository ingredients;

	private final JdbcTemplate jdbc;

	private final AuditLog audit;

	private final EntityManager entityManager;

	public InventoryAdminService(IngredientRepository ingredients, JdbcTemplate jdbc, AuditLog audit,
			EntityManager entityManager) {
		this.ingredients = ingredients;
		this.jdbc = jdbc;
		this.audit = audit;
		this.entityManager = entityManager;
	}

	@Transactional(readOnly = true)
	public List<IngredientResponse> all() {
		return ingredients.findAllByOrderByNameAsc().stream().map(IngredientResponse::from).toList();
	}

	@Transactional
	public IngredientResponse create(UUID actor, IngredientCreate create) {
		String name = create.name().strip();
		if (ingredients.existsByName(name)) {
			throw exists();
		}
		Ingredient ingredient;
		try {
			ingredient = ingredients.saveAndFlush(
					new Ingredient(name, create.unit().strip(), create.onHandOrZero(), create.lowStockThreshold()));
		}
		catch (DataIntegrityViolationException ex) {
			throw exists();
		}
		if (create.onHandOrZero().isPositive()) {
			jdbc.update("""
					INSERT INTO inventory_movements
					  (ingredient_id, movement_type, quantity_delta, on_hand_after, actor_type, actor_user_id, reason)
					VALUES (?, 'INITIAL', ?, ?, 'ADMIN', ?, 'Initial stock')""", ingredient.getId(),
					create.onHandOrZero().value(), create.onHandOrZero().value(), actor);
		}
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("name", ingredient.getName());
		details.put("unit", ingredient.getUnit());
		details.put("onHand", ingredient.getOnHand().toString());
		audit.admin(actor, "INGREDIENT_CREATED", "ingredient", ingredient.getId(), null, details);
		return IngredientResponse.from(reload(ingredient.getId()));
	}

	/**
	 * Sets on-hand under a row lock. A no-op (same quantity) records nothing: the ledger only holds
	 * real changes ({@code quantity_delta <> 0}).
	 */
	@Transactional
	public IngredientResponse adjust(UUID actor, UUID id, InventoryAdjustment adjustment) {
		List<Map<String, Object>> locked = jdbc.queryForList(
				"SELECT on_hand, reserved FROM ingredients WHERE id = ? FOR UPDATE", id);
		if (locked.isEmpty()) {
			throw notFound();
		}
		Quantity onHand = Quantity.of((BigDecimal) locked.get(0).get("on_hand"));
		Quantity reserved = Quantity.of((BigDecimal) locked.get(0).get("reserved"));
		Quantity target = adjustment.onHand();
		if (target.compareTo(reserved) < 0) {
			throw ApiException.conflict(BELOW_RESERVED,
					"On-hand cannot be set below the " + reserved + " already reserved for orders.");
		}
		if (!target.equals(onHand)) {
			BigDecimal delta = target.value().subtract(onHand.value());
			String reason = adjustment.reason().strip();
			jdbc.update("UPDATE ingredients SET on_hand = ? WHERE id = ?", target.value(), id);
			jdbc.update("""
					INSERT INTO inventory_movements
					  (ingredient_id, movement_type, quantity_delta, on_hand_after, actor_type, actor_user_id, reason)
					VALUES (?, 'ADJUSTMENT', ?, ?, 'ADMIN', ?, ?)""", id, delta, target.value(), actor, reason);
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("from", onHand.toString());
			details.put("to", target.toString());
			audit.admin(actor, "INVENTORY_ADJUSTED", "ingredient", id, reason, details);
		}
		return IngredientResponse.from(reload(id));
	}

	@Transactional(readOnly = true)
	public PageResponse<MovementResponse> movements(UUID ingredientId, int page, int size) {
		if (!ingredients.existsById(ingredientId)) {
			throw notFound();
		}
		Long total = jdbc.queryForObject("SELECT count(*) FROM inventory_movements WHERE ingredient_id = ?", Long.class,
				ingredientId);
		List<MovementResponse> content = jdbc.query("""
				SELECT id, ingredient_id, movement_type::text AS movement_type, quantity_delta, on_hand_after, order_id,
				       actor_type::text AS actor_type, actor_user_id, reason, created_at
				FROM inventory_movements WHERE ingredient_id = ?
				ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?""",
				(rs, row) -> new MovementResponse(rs.getObject("id", UUID.class),
						rs.getObject("ingredient_id", UUID.class), rs.getString("movement_type"),
						rs.getBigDecimal("quantity_delta").setScale(Quantity.SCALE),
						Quantity.of(rs.getBigDecimal("on_hand_after")), rs.getObject("order_id", UUID.class),
						rs.getString("actor_type"), rs.getObject("actor_user_id", UUID.class), rs.getString("reason"),
						rs.getObject("created_at", Timestamp.class).toInstant()),
				ingredientId, size, (long) page * size);
		return new PageResponse<>(content, page, size, total == null ? 0 : total);
	}

	/** Fresh state, including database-maintained {@code reserved}. */
	private Ingredient reload(UUID id) {
		entityManager.flush();
		entityManager.clear();
		return ingredients.findById(id).orElseThrow(InventoryAdminService::notFound);
	}

	private static ApiException notFound() {
		return ApiException.notFound(INGREDIENT_NOT_FOUND, "Ingredient not found.");
	}

	private static ApiException exists() {
		return ApiException.conflict(INGREDIENT_EXISTS, "An ingredient with this name already exists.");
	}

}
