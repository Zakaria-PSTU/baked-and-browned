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
 * Product recipes: ingredient consumption per product unit, and the stock a cart needs. A product
 * without a recipe needs no stock.
 */
@Service
public class RecipeService {

	public static final String UNKNOWN_INGREDIENT = "UNKNOWN_INGREDIENT";

	public static final String INVALID_RECIPE = "INVALID_RECIPE";

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
		jdbc.update("DELETE FROM product_ingredients WHERE product_id = ?", productId);
		for (RecipeLine line : lines) {
			jdbc.update("INSERT INTO product_ingredients (product_id, ingredient_id, quantity_per_unit) VALUES (?, ?, ?)",
					productId, line.ingredientId(), line.quantityPerUnit().value());
		}
		List<RecipeLine> saved = lines(productId);
		audit.admin(actor, "PRODUCT_RECIPE_SET", "product", productId, null, Map.of("lines",
				saved.stream().map(l -> Map.of("ingredient", l.ingredientName(), "perUnit", l.quantityPerUnit().toString())).toList()));
		return saved;
	}

	/**
	 * Total stock needed for {@code quantities} (product id to number of items), per ingredient, in
	 * ascending ingredient-id order: the order reservations must lock rows in.
	 */
	@Transactional(readOnly = true)
	public TreeMap<UUID, Quantity> requirements(Map<UUID, Integer> quantities) {
		TreeMap<UUID, Quantity> needed = new TreeMap<>();
		if (quantities.isEmpty()) {
			return needed;
		}
		jdbc.query(con -> {
			var ps = con.prepareStatement(
					"SELECT product_id, ingredient_id, quantity_per_unit FROM product_ingredients WHERE product_id = ANY (?)");
			ps.setArray(1, con.createArrayOf("uuid", quantities.keySet().toArray()));
			return ps;
		}, rs -> {
			UUID product = rs.getObject("product_id", UUID.class);
			UUID ingredient = rs.getObject("ingredient_id", UUID.class);
			Quantity perUnit = Quantity.of(rs.getBigDecimal("quantity_per_unit"));
			needed.merge(ingredient, perUnit.times(quantities.get(product)), Quantity::plus);
		});
		return needed;
	}

	private List<RecipeLine> lines(UUID productId) {
		List<RecipeLine> lines = new ArrayList<>();
		jdbc.query("""
				SELECT pi.ingredient_id, i.name, i.unit, pi.quantity_per_unit
				FROM product_ingredients pi JOIN ingredients i ON i.id = pi.ingredient_id
				WHERE pi.product_id = ? ORDER BY i.name""", rs -> {
			BigDecimal perUnit = rs.getBigDecimal("quantity_per_unit");
			lines.add(new RecipeLine(rs.getObject("ingredient_id", UUID.class), rs.getString("name"), rs.getString("unit"),
					Quantity.of(perUnit)));
		}, productId);
		return lines;
	}

}
