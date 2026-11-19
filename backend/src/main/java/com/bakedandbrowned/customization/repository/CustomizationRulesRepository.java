package com.bakedandbrowned.customization.repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.bakedandbrowned.common.value.Money;
import com.bakedandbrowned.customization.entity.SelectionType;
import com.bakedandbrowned.customization.service.CustomizationRules;
import com.bakedandbrowned.customization.service.CustomizationRules.GroupRule;
import com.bakedandbrowned.customization.service.CustomizationRules.OptionPair;
import com.bakedandbrowned.customization.service.CustomizationRules.OptionRule;

/**
 * Loads {@link CustomizationRules} for products in one query: assigned groups ordered by code, each
 * with every option allowed for the product (active or not) ordered by name.
 */
@Repository
public class CustomizationRulesRepository {

	/** Database limit for TEXT messages ({@code VARCHAR(250)}). */
	static final int TEXT_COLUMN_LIMIT = 250;

	private final JdbcTemplate jdbc;

	public CustomizationRulesRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public CustomizationRules forProduct(UUID productId) {
		return forProducts(List.of(productId)).get(productId);
	}

	/** Rules per product id; products without assignments get empty rules. */
	public Map<UUID, CustomizationRules> forProducts(Collection<UUID> productIds) {
		Map<UUID, Map<UUID, GroupDraft>> drafts = new LinkedHashMap<>();
		productIds.forEach(id -> drafts.put(id, new LinkedHashMap<>()));
		if (!productIds.isEmpty()) {
			jdbc.query(con -> {
				var ps = con.prepareStatement("""
						SELECT pcg.product_id, g.id AS group_id, g.code, g.name AS group_name, g.selection_type,
						       g.min_selections, g.max_selections, pcg.required, pcg.text_max_length,
						       o.id AS option_id, o.name AS option_name, o.price_delta, o.active,
						       o.extra_production_units, o.min_lead_hours
						FROM product_customization_groups pcg
						JOIN customization_groups g ON g.id = pcg.group_id
						LEFT JOIN product_customization_options pco
						       ON pco.product_id = pcg.product_id AND pco.group_id = pcg.group_id
						LEFT JOIN customization_options o ON o.id = pco.option_id
						WHERE pcg.product_id = ANY (?)
						ORDER BY pcg.product_id, pcg.display_order, g.code, pco.display_order, o.name, o.id""");
				ps.setArray(1, con.createArrayOf("uuid", productIds.toArray()));
				return ps;
			}, rs -> {
				UUID productId = rs.getObject("product_id", UUID.class);
				UUID groupId = rs.getObject("group_id", UUID.class);
				SelectionType type = SelectionType.valueOf(rs.getString("selection_type"));
				Integer productLimit = rs.getObject("text_max_length", Integer.class);
				Integer textLimit = type == SelectionType.TEXT
						? (productLimit == null ? TEXT_COLUMN_LIMIT : productLimit) : null;
				GroupDraft candidate = new GroupDraft(groupId, rs.getString("code"), rs.getString("group_name"), type,
						rs.getBoolean("required"), rs.getInt("min_selections"), rs.getInt("max_selections"), textLimit);
				GroupDraft group = drafts.get(productId).computeIfAbsent(groupId, id -> candidate);
				UUID optionId = rs.getObject("option_id", UUID.class);
				if (optionId != null) {
					group.options.put(optionId, new OptionRule(optionId, rs.getString("option_name"),
							Money.of(rs.getBigDecimal("price_delta")), rs.getBoolean("active"),
							rs.getInt("extra_production_units"), rs.getObject("min_lead_hours", Integer.class)));
				}
			});
		}
		Map<UUID, List<OptionPair>> pairs = disallowedPairs(productIds);
		Map<UUID, CustomizationRules> rules = new LinkedHashMap<>();
		drafts.forEach((id, groups) -> rules.put(id, new CustomizationRules(
				groups.values().stream().map(GroupDraft::build).toList(), pairs.getOrDefault(id, List.of()))));
		return rules;
	}

	/** Disallowed option pairs per product (ADR-0020); products without any are absent. */
	public Map<UUID, List<OptionPair>> disallowedPairs(Collection<UUID> productIds) {
		Map<UUID, List<OptionPair>> pairs = new LinkedHashMap<>();
		if (productIds.isEmpty()) {
			return pairs;
		}
		jdbc.query(con -> {
			var ps = con.prepareStatement("""
					SELECT product_id, option_a, option_b FROM product_disallowed_option_pairs
					WHERE product_id = ANY (?) ORDER BY product_id, option_a, option_b""");
			ps.setArray(1, con.createArrayOf("uuid", productIds.toArray()));
			return ps;
		}, rs -> {
			pairs.computeIfAbsent(rs.getObject("product_id", UUID.class), id -> new ArrayList<>())
				.add(new OptionPair(rs.getObject("option_a", UUID.class), rs.getObject("option_b", UUID.class)));
		});
		return pairs;
	}

	private record GroupDraft(UUID id, String code, String name, SelectionType type, boolean required, int min,
			int max, Integer textLimit, Map<UUID, OptionRule> options) {

		GroupDraft(UUID id, String code, String name, SelectionType type, boolean required, int min, int max,
				Integer textLimit) {
			this(id, code, name, type, required, min, max, textLimit, new LinkedHashMap<>());
		}

		GroupRule build() {
			return new GroupRule(id, code, name, type, required, min, max, textLimit, options);
		}

	}

}
