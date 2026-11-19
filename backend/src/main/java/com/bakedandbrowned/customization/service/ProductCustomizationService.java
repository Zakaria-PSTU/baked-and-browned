package com.bakedandbrowned.customization.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bakedandbrowned.audit.service.AuditLog;
import com.bakedandbrowned.catalog.service.ProductCatalog;
import com.bakedandbrowned.common.web.ApiException;
import com.bakedandbrowned.customization.dto.CustomizationDtos.AssignmentWrite;
import com.bakedandbrowned.customization.dto.CustomizationDtos.DisallowedCombination;
import com.bakedandbrowned.customization.entity.CustomizationGroup;
import com.bakedandbrowned.customization.entity.SelectionType;
import com.bakedandbrowned.customization.repository.CustomizationGroupRepository;
import com.bakedandbrowned.customization.repository.CustomizationOptionRepository;
import com.bakedandbrowned.customization.repository.CustomizationRulesRepository;

/**
 * Assigns customization groups to products and validates customers' choices against them.
 * <p>
 * Replacing a product's assignments locks the product row first, so concurrent replacements
 * serialize, then validates the whole set before writing anything (422 on the first problem). The
 * order of the list, and of each assignment's options, is the order customers see (ADR-0021).
 */
@Service
public class ProductCustomizationService {

	public static final String DUPLICATE_GROUP = "DUPLICATE_GROUP";

	public static final String UNKNOWN_GROUP = "UNKNOWN_GROUP";

	public static final String OPTION_NOT_IN_GROUP = "OPTION_NOT_IN_GROUP";

	public static final String INVALID_ASSIGNMENT = "INVALID_ASSIGNMENT";

	public static final String INVALID_COMBINATION = "INVALID_COMBINATION";

	private final ProductCatalog catalog;

	private final CustomizationGroupRepository groups;

	private final CustomizationOptionRepository options;

	private final CustomizationRulesRepository rules;

	private final JdbcTemplate jdbc;

	private final AuditLog audit;

	public ProductCustomizationService(ProductCatalog catalog, CustomizationGroupRepository groups,
			CustomizationOptionRepository options, CustomizationRulesRepository rules, JdbcTemplate jdbc,
			AuditLog audit) {
		this.catalog = catalog;
		this.groups = groups;
		this.options = options;
		this.rules = rules;
		this.jdbc = jdbc;
		this.audit = audit;
	}

	/** Replaces all of the product's assignments with {@code assignments} (an empty list removes them all). */
	@Transactional
	public void replaceAssignments(UUID actor, UUID productId, List<AssignmentWrite> assignments) {
		catalog.lockForUpdate(productId);

		Set<UUID> groupIds = new HashSet<>();
		for (AssignmentWrite a : assignments) {
			if (!groupIds.add(a.groupId())) {
				throw ApiException.unprocessable(DUPLICATE_GROUP, "A customization group is assigned more than once.");
			}
		}
		Map<UUID, CustomizationGroup> found = new HashMap<>();
		groups.findAllById(groupIds).forEach(g -> found.put(g.getId(), g));
		Map<UUID, UUID> groupOfOption = new HashMap<>();
		options.findByGroupIds(groupIds).forEach(o -> groupOfOption.put(o.getId(), o.getGroup().getId()));

		List<Map<String, Object>> summary = new ArrayList<>();
		for (AssignmentWrite a : assignments) {
			CustomizationGroup group = found.get(a.groupId());
			if (group == null) {
				throw ApiException.unprocessable(UNKNOWN_GROUP, "groupId " + a.groupId() + ": no such group.");
			}
			check(group, a, groupOfOption);
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("groupCode", group.getCode());
			item.put("required", a.required());
			item.put("allowedOptions", a.allowedOptionIds().size());
			item.put("textMaxLength", a.textMaxLength());
			summary.add(item);
		}

		// Child rows (allowed options) go with their assignment (ON DELETE CASCADE).
		jdbc.update("DELETE FROM product_customization_groups WHERE product_id = ?", productId);
		// List order is display order (ADR-0021): groups as listed, each group's options as listed.
		for (int g = 0; g < assignments.size(); g++) {
			AssignmentWrite a = assignments.get(g);
			jdbc.update("""
					INSERT INTO product_customization_groups (product_id, group_id, required, text_max_length, display_order)
					VALUES (?, ?, ?, ?, ?)""", productId, a.groupId(), a.required(), a.textMaxLength(), g);
			for (int o = 0; o < a.allowedOptionIds().size(); o++) {
				jdbc.update("""
						INSERT INTO product_customization_options (product_id, group_id, option_id, display_order)
						VALUES (?, ?, ?, ?)""", productId, a.groupId(), a.allowedOptionIds().get(o), o);
			}
		}
		// A disallowed pair only makes sense while the product still allows both of its options.
		int dropped = jdbc.update("""
				DELETE FROM product_disallowed_option_pairs d
				WHERE d.product_id = ?
				  AND (NOT EXISTS (SELECT 1 FROM product_customization_options a
				                   WHERE a.product_id = d.product_id AND a.option_id = d.option_a)
				    OR NOT EXISTS (SELECT 1 FROM product_customization_options b
				                   WHERE b.product_id = d.product_id AND b.option_id = d.option_b))""", productId);
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("assignments", summary);
		details.put("disallowedCombinationsDropped", dropped);
		audit.admin(actor, "PRODUCT_CUSTOMIZATIONS_SET", "product", productId, null, details);
	}

	/** The product's disallowed combinations (ADR-0020), in a stable order. */
	@Transactional(readOnly = true)
	public List<DisallowedCombination> disallowedCombinations(UUID productId) {
		catalog.requireExists(productId);
		return combinations(productId);
	}

	/**
	 * Replaces the product's disallowed combinations (an empty list removes them), under the product
	 * row lock. Each names two different options the product currently allows; the same pair given
	 * twice, in either order, is rejected.
	 * @throws ApiException 422 {@link #INVALID_COMBINATION}; nothing changes then
	 */
	@Transactional
	public List<DisallowedCombination> replaceDisallowedCombinations(UUID actor, UUID productId,
			List<DisallowedCombination> combinations) {
		catalog.lockForUpdate(productId);
		Set<UUID> allowed = new HashSet<>(jdbc.queryForList(
				"SELECT option_id FROM product_customization_options WHERE product_id = ?", UUID.class, productId));
		Set<List<UUID>> pairs = new java.util.LinkedHashSet<>();
		for (DisallowedCombination combination : combinations) {
			UUID first = combination.optionIds().get(0);
			UUID second = combination.optionIds().get(1);
			if (first.equals(second)) {
				throw ApiException.unprocessable(INVALID_COMBINATION, "A combination needs two different options.");
			}
			if (!allowed.contains(first) || !allowed.contains(second)) {
				throw ApiException.unprocessable(INVALID_COMBINATION,
						"A combination names an option this product does not offer.");
			}
			if (!pairs.add(ordered(first, second))) {
				throw ApiException.unprocessable(INVALID_COMBINATION, "The same combination is listed more than once.");
			}
		}
		jdbc.update("DELETE FROM product_disallowed_option_pairs WHERE product_id = ?", productId);
		for (List<UUID> pair : pairs) {
			jdbc.update("INSERT INTO product_disallowed_option_pairs (product_id, option_a, option_b) VALUES (?, ?, ?)",
					productId, pair.get(0), pair.get(1));
		}
		List<DisallowedCombination> saved = combinations(productId);
		audit.admin(actor, "PRODUCT_DISALLOWED_COMBINATIONS_SET", "product", productId, null,
				Map.of("combinations", saved.stream().map(c -> c.optionIds().stream().map(UUID::toString).toList()).toList()));
		return saved;
	}

	private List<DisallowedCombination> combinations(UUID productId) {
		return rules.disallowedPairs(List.of(productId))
			.getOrDefault(productId, List.of())
			.stream()
			.map(p -> new DisallowedCombination(List.of(p.first(), p.second())))
			.toList();
	}

	/**
	 * The pair in the order PostgreSQL sorts {@code uuid} (unsigned bytes, the same as the canonical
	 * text), which the table's {@code option_a < option_b} check needs. {@link UUID#compareTo} uses
	 * signed longs and can disagree.
	 */
	static List<UUID> ordered(UUID one, UUID other) {
		return one.toString().compareTo(other.toString()) < 0 ? List.of(one, other) : List.of(other, one);
	}

	/**
	 * Validates one cart line's customization choices against the product's current rules.
	 * @throws ApiException 422 with a {@link SelectionValidator} code
	 */
	@Transactional(readOnly = true)
	public ValidatedCustomization validateSelection(UUID productId, List<UUID> selectedOptionIds,
			Map<UUID, String> customTexts) {
		return SelectionValidator.validate(rules.forProduct(productId), selectedOptionIds, customTexts);
	}

	private static void check(CustomizationGroup group, AssignmentWrite a, Map<UUID, UUID> groupOfOption) {
		String label = "\"" + group.getCode() + "\": ";
		if (new HashSet<>(a.allowedOptionIds()).size() != a.allowedOptionIds().size()) {
			throw invalid(label + "allowedOptionIds contains duplicates.");
		}
		for (UUID optionId : a.allowedOptionIds()) {
			if (!group.getId().equals(groupOfOption.get(optionId))) {
				throw ApiException.unprocessable(OPTION_NOT_IN_GROUP,
						label + "option " + optionId + " does not belong to this group.");
			}
		}
		if (group.getSelectionType() == SelectionType.TEXT) {
			if (!a.allowedOptionIds().isEmpty()) {
				throw invalid(label + "TEXT groups take no options.");
			}
			return;
		}
		if (a.textMaxLength() != null) {
			throw invalid(label + "textMaxLength applies to TEXT groups only.");
		}
		int needed = Math.max(group.getMinSelections(), 1);
		if (a.required() && a.allowedOptionIds().size() < needed) {
			throw invalid(label + "a required group must allow at least " + needed + " option(s).");
		}
	}

	private static ApiException invalid(String detail) {
		return ApiException.unprocessable(INVALID_ASSIGNMENT, detail);
	}

}
