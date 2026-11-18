package com.bakedandbrowned.inventory.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bakedandbrowned.audit.service.AuditLog;
import com.bakedandbrowned.catalog.service.ProductCatalog;
import com.bakedandbrowned.common.value.Quantity;
import com.bakedandbrowned.common.web.ApiException;
import com.bakedandbrowned.inventory.dto.InventoryDtos.RecipeLine;

/**
 * Recipes: ingredient consumption per product unit and, on top of it, per chosen customization
 * option (ADR-0019), and the stock a cart needs. A product or option without lines needs no stock.
 */
@Service
public class RecipeService {

	public static final String UNKNOWN_INGREDIENT = "UNKNOWN_INGREDIENT";

	public static final String INVALID_RECIPE = "INVALID_RECIPE";

	public static final String OPTION_NOT_FOUND = "OPTION_NOT_FOUND";

	private final ProductCatalog catalog;

	private final JdbcTemplate jdbc;

	private final AuditLog audit;

	public RecipeService(ProductCatalog catalog, JdbcTemplate jdbc, AuditLog audit) {
		this.catalog = catalog;
		this.jdbc = jdbc;
		this.audit = audit;
	}

	@Transactional(readOnly = true)
	public List<RecipeLine> recipe(UUID productId) {
		catalog.requireExists(productId);
		return lines(productId);
	}

	/** Replaces the product's recipe (an empty list removes it), under the product row lock. */
	@Transactional
	public List<RecipeLine> replace(UUID actor, UUID productId, List<RecipeLine> lines) {
		catalog.lockForUpdate(productId);
		check(lines);
		jdbc.update("DELETE FROM product_ingredients WHERE product_id = ?", productId);
		for (RecipeLine line : lines) {
			jdbc.update("INSERT INTO product_ingredients (product_id, ingredient_id, quantity_per_unit) VALUES (?, ?, ?)",
					productId, line.ingredientId(), line.quantityPerUnit().value());
		}
		List<RecipeLine> saved = lines(productId);
		audit.admin(actor, "PRODUCT_RECIPE_SET", "product", productId, null, Map.of("lines", described(saved)));
		return saved;
	}

	/** What one customization option consumes per item, on top of the product recipe. */
	@Transactional(readOnly = true)
	public List<RecipeLine> optionRecipe(UUID optionId) {
		requireOption(optionId, false);
		return optionLines(optionId);
	}

	/** Replaces an option's ingredients (an empty list removes them), under the option row lock. */
	@Transactional
	public List<RecipeLine> replaceOptionRecipe(UUID actor, UUID optionId, List<RecipeLine> lines) {
		requireOption(optionId, true);
		check(lines);
		jdbc.update("DELETE FROM option_ingredients WHERE option_id = ?", optionId);
		for (RecipeLine line : lines) {
			jdbc.update("INSERT INTO option_ingredients (option_id, ingredient_id, quantity_per_unit) VALUES (?, ?, ?)",
					optionId, line.ingredientId(), line.quantityPerUnit().value());
		}
		List<RecipeLine> saved = optionLines(optionId);
		audit.admin(actor, "OPTION_RECIPE_SET", "customization_option", optionId, null,
				Map.of("lines", described(saved)));
		return saved;
	}

	/** Duplicates, non-positive quantities and unknown ingredients are 422; nothing is written then. */
	private void check(List<RecipeLine> lines) {
		Set<UUID> seen = new HashSet<>();
		for (RecipeLine line : lines) {
			if (!seen.add(line.ingredientId())) {
				throw ApiException.unprocessable(INVALID_RECIPE, "An ingredient appears more than once.");
			}
			if (!line.quantityPerUnit().isPositive()) {
				throw ApiException.unprocessable(INVALID_RECIPE, "quantityPerUnit must be greater than zero.");
			}
		}
		if (!seen.isEmpty()) {
			Integer known = jdbc.query(con -> {
				var ps = con.prepareStatement("SELECT count(*) FROM ingredients WHERE id = ANY (?)");
				ps.setArray(1, con.createArrayOf("uuid", seen.toArray()));
				return ps;
			}, rs -> rs.next() ? rs.getInt(1) : 0);
			if (known == null || known != seen.size()) {
				throw ApiException.unprocessable(UNKNOWN_INGREDIENT, "A recipe line refers to an unknown ingredient.");
			}
		}
	}

	/**
	 * Total stock needed for {@code quantities} (product id to number of items), per ingredient, in
	 * ascending ingredient-id order: the order reservations must lock rows in.
	 */
	@Transactional(readOnly = true)
	public TreeMap<UUID, Quantity> requirements(Map<UUID, Integer> quantities) {
		return requirements(quantities, Map.of());
	}

	/**
	 * As {@link #requirements(Map)}, plus what the chosen options consume: {@code optionQuantities}
	 * maps option id to the number of items it was chosen on.
	 */
	@Transactional(readOnly = true)
	public TreeMap<UUID, Quantity> requirements(Map<UUID, Integer> quantities, Map<UUID, Integer> optionQuantities) {
		TreeMap<UUID, Quantity> needed = new TreeMap<>();
		add(needed, "product_ingredients", "product_id", quantities);
		add(needed, "option_ingredients", "option_id", optionQuantities);
		return needed;
	}

	/** {@code table} and {@code owner} are constants of this class, never input. */
	private void add(TreeMap<UUID, Quantity> needed, String table, String owner, Map<UUID, Integer> quantities) {
		if (quantities.isEmpty()) {
			return;
		}
		jdbc.query(con -> {
			var ps = con.prepareStatement(
					"SELECT " + owner + ", ingredient_id, quantity_per_unit FROM " + table + " WHERE " + owner + " = ANY (?)");
			ps.setArray(1, con.createArrayOf("uuid", quantities.keySet().toArray()));
			return ps;
		}, rs -> {
			UUID ownerId = rs.getObject(owner, UUID.class);
			UUID ingredient = rs.getObject("ingredient_id", UUID.class);
			Quantity perUnit = Quantity.of(rs.getBigDecimal("quantity_per_unit"));
			needed.merge(ingredient, perUnit.times(quantities.get(ownerId)), Quantity::plus);
		});
	}

	private void requireOption(UUID optionId, boolean lock) {
		List<UUID> found = jdbc.queryForList(
				"SELECT id FROM customization_options WHERE id = ?" + (lock ? " FOR UPDATE" : ""), UUID.class, optionId);
		if (found.isEmpty()) {
			throw ApiException.notFound(OPTION_NOT_FOUND, "Customization option not found.");
		}
	}

	private static List<Map<String, String>> described(List<RecipeLine> lines) {
		return lines.stream()
			.map(l -> Map.of("ingredient", String.valueOf(l.ingredientName()), "perUnit", l.quantityPerUnit().toString()))
			.toList();
	}

	private List<RecipeLine> lines(UUID productId) {
		return lines("product_ingredients", "product_id", productId);
	}

	private List<RecipeLine> optionLines(UUID optionId) {
		return lines("option_ingredients", "option_id", optionId);
	}

	private List<RecipeLine> lines(String table, String owner, UUID ownerId) {
		List<RecipeLine> lines = new ArrayList<>();
		jdbc.query("SELECT r.ingredient_id, i.name, i.unit, r.quantity_per_unit FROM " + table
				+ " r JOIN ingredients i ON i.id = r.ingredient_id WHERE r." + owner + " = ? ORDER BY i.name", rs -> {
					BigDecimal perUnit = rs.getBigDecimal("quantity_per_unit");
					lines.add(new RecipeLine(rs.getObject("ingredient_id", UUID.class), rs.getString("name"),
							rs.getString("unit"), Quantity.of(perUnit)));
				}, ownerId);
		return lines;
	}

}
