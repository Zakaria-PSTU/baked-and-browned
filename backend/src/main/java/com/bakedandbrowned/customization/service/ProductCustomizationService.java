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
import com.bakedandbrowned.customization.entity.CustomizationGroup;
import com.bakedandbrowned.customization.entity.SelectionType;
import com.bakedandbrowned.customization.repository.CustomizationGroupRepository;
import com.bakedandbrowned.customization.repository.CustomizationOptionRepository;
import com.bakedandbrowned.customization.repository.CustomizationRulesRepository;

/**
 * Assigns customization groups to products and validates customers' choices against them.
 * <p>
 * Replacing a product's assignments locks the product row first, so concurrent replacements
 * serialize, then validates the whole set before writing anything (422 on the first problem).
 */
@Service
public class ProductCustomizationService {

	public static final String DUPLICATE_GROUP = "DUPLICATE_GROUP";

	public static final String UNKNOWN_GROUP = "UNKNOWN_GROUP";

	public static final String OPTION_NOT_IN_GROUP = "OPTION_NOT_IN_GROUP";

	public static final String INVALID_ASSIGNMENT = "INVALID_ASSIGNMENT";

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
		for (AssignmentWrite a : assignments) {
			jdbc.update("""
					INSERT INTO product_customization_groups (product_id, group_id, required, text_max_length)
					VALUES (?, ?, ?, ?)""", productId, a.groupId(), a.required(), a.textMaxLength());
			for (UUID optionId : a.allowedOptionIds()) {
				jdbc.update("INSERT INTO product_customization_options (product_id, group_id, option_id) VALUES (?, ?, ?)",
						productId, a.groupId(), optionId);
			}
		}
		audit.admin(actor, "PRODUCT_CUSTOMIZATIONS_SET", "product", productId, null, Map.of("assignments", summary));
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
