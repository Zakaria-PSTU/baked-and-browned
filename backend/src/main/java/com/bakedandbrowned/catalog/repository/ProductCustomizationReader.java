package com.bakedandbrowned.catalog.repository;

import java.math.BigDecimal;
import java.sql.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.bakedandbrowned.common.value.Money;

/**
 * Read model of the customizations offered with products, loaded for a whole page of products in
 * one query (no N+1). Only options that are active and explicitly allowed for the product are
 * included. Groups are ordered by code, options by name.
 */
@Repository
public class ProductCustomizationReader {

	/** Database limit for TEXT messages; a product may set a stricter one. */
	public static final int TEXT_MAX_LENGTH = 250;

	private final JdbcTemplate jdbc;

	public ProductCustomizationReader(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public record OptionView(UUID id, String name, Money priceDelta, @Nullable Integer minLeadHours) {
	}

	/**
	 * @param textMaxLength the effective limit for TEXT groups (the product's own, else
	 * {@value #TEXT_MAX_LENGTH}); null for other selection types
	 */
	public record GroupView(UUID id, String code, String name, String selectionType, boolean required,
			int minSelections, int maxSelections, @Nullable Integer textMaxLength, List<OptionView> options) {
	}

	/** Groups per product id; products without customizations map to an empty list. */
	public Map<UUID, List<GroupView>> forProducts(Collection<UUID> productIds) {
		Map<UUID, Map<UUID, GroupBuilder>> byProduct = new LinkedHashMap<>();
		productIds.forEach(id -> byProduct.put(id, new LinkedHashMap<>()));
		if (productIds.isEmpty()) {
			return Map.of();
		}
		jdbc.query(con -> {
			var ps = con.prepareStatement("""
					SELECT pcg.product_id, g.id AS group_id, g.code, g.name AS group_name, g.selection_type,
					       g.min_selections, g.max_selections, pcg.required, pcg.text_max_length,
					       o.id AS option_id, o.name AS option_name, o.price_delta, o.min_lead_hours
					FROM product_customization_groups pcg
					JOIN customization_groups g ON g.id = pcg.group_id
					LEFT JOIN product_customization_options pco
					       ON pco.product_id = pcg.product_id AND pco.group_id = pcg.group_id
					LEFT JOIN customization_options o ON o.id = pco.option_id AND o.active
					WHERE pcg.product_id = ANY (?)
					ORDER BY pcg.product_id, g.code, o.name, o.id""");
			Array ids = con.createArrayOf("uuid", productIds.toArray());
			ps.setArray(1, ids);
			return ps;
		}, rs -> {
			UUID productId = rs.getObject("product_id", UUID.class);
			UUID groupId = rs.getObject("group_id", UUID.class);
			String type = rs.getString("selection_type");
			Integer productTextMax = rs.getObject("text_max_length", Integer.class);
			Integer textMax = "TEXT".equals(type) ? (productTextMax == null ? TEXT_MAX_LENGTH : productTextMax) : null;
			GroupBuilder candidate = new GroupBuilder(groupId, rs.getString("code"), rs.getString("group_name"), type,
					rs.getBoolean("required"), rs.getInt("min_selections"), rs.getInt("max_selections"), textMax);
			GroupBuilder group = byProduct.get(productId).computeIfAbsent(groupId, id -> candidate);
			UUID optionId = rs.getObject("option_id", UUID.class);
			if (optionId != null) {
				BigDecimal delta = rs.getBigDecimal("price_delta");
				group.options.add(new OptionView(optionId, rs.getString("option_name"), Money.of(delta),
						rs.getObject("min_lead_hours", Integer.class)));
			}
		});
		Map<UUID, List<GroupView>> result = new LinkedHashMap<>();
		byProduct.forEach((id, groups) -> result.put(id, groups.values().stream().map(GroupBuilder::build).toList()));
		return result;
	}

	public List<GroupView> forProduct(UUID productId) {
		return forProducts(List.of(productId)).getOrDefault(productId, List.of());
	}

	/**
	 * Disallowed combinations per product (ADR-0020), as option-id pairs, leaving out pairs with an
	 * inactive option (customers never see those options). Products without any are absent.
	 */
	public Map<UUID, List<List<UUID>>> disallowedCombinations(Collection<UUID> productIds) {
		Map<UUID, List<List<UUID>>> pairs = new LinkedHashMap<>();
		if (productIds.isEmpty()) {
			return pairs;
		}
		jdbc.query(con -> {
			var ps = con.prepareStatement("""
					SELECT d.product_id, d.option_a, d.option_b FROM product_disallowed_option_pairs d
					JOIN customization_options a ON a.id = d.option_a AND a.active
					JOIN customization_options b ON b.id = d.option_b AND b.active
					WHERE d.product_id = ANY (?) ORDER BY d.product_id, d.option_a, d.option_b""");
			ps.setArray(1, con.createArrayOf("uuid", productIds.toArray()));
			return ps;
		}, rs -> {
			pairs.computeIfAbsent(rs.getObject("product_id", UUID.class), id -> new ArrayList<>())
				.add(List.of(rs.getObject("option_a", UUID.class), rs.getObject("option_b", UUID.class)));
		});
		return pairs;
	}

	public List<List<UUID>> disallowedCombinations(UUID productId) {
		return disallowedCombinations(List.of(productId)).getOrDefault(productId, List.of());
	}

	private static final class GroupBuilder {

		private final UUID id;

		private final String code;

		private final String name;

		private final String selectionType;

		private final boolean required;

		private final int minSelections;

		private final int maxSelections;

		private final @Nullable Integer textMaxLength;

		private final List<OptionView> options = new ArrayList<>();

		GroupBuilder(UUID id, String code, String name, String selectionType, boolean required, int minSelections,
				int maxSelections, @Nullable Integer textMaxLength) {
			this.id = id;
			this.code = code;
			this.name = name;
			this.selectionType = selectionType;
			this.required = required;
			this.minSelections = minSelections;
			this.maxSelections = maxSelections;
			this.textMaxLength = textMaxLength;
		}

		GroupView build() {
			return new GroupView(id, code, name, selectionType, required, minSelections, maxSelections, textMaxLength,
					List.copyOf(options));
		}

	}

}
