package com.bakedandbrowned.customization.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bakedandbrowned.audit.service.AuditLog;
import com.bakedandbrowned.common.web.ApiException;
import com.bakedandbrowned.customization.dto.CustomizationDtos.AdminGroupResponse;
import com.bakedandbrowned.customization.dto.CustomizationDtos.AdminOptionResponse;
import com.bakedandbrowned.customization.dto.CustomizationDtos.GroupWrite;
import com.bakedandbrowned.customization.dto.CustomizationDtos.OptionWrite;
import com.bakedandbrowned.customization.entity.CustomizationGroup;
import com.bakedandbrowned.customization.entity.CustomizationOption;
import com.bakedandbrowned.customization.entity.SelectionType;
import com.bakedandbrowned.customization.repository.CustomizationGroupRepository;
import com.bakedandbrowned.customization.repository.CustomizationOptionRepository;

/**
 * Admin management of customization groups and options. Every change is audited. Option changes
 * never affect placed orders, which keep their own snapshots.
 * <p>
 * Group shape rules (422 {@code INVALID_GROUP}): {@code maxSelections >= minSelections}; SINGLE and
 * TEXT groups allow exactly one selection at most ({@code maxSelections = 1}).
 */
@Service
public class CustomizationAdminService {

	public static final String GROUP_NOT_FOUND = "GROUP_NOT_FOUND";

	public static final String OPTION_NOT_FOUND = "OPTION_NOT_FOUND";

	public static final String GROUP_CODE_TAKEN = "GROUP_CODE_TAKEN";

	public static final String OPTION_NAME_TAKEN = "OPTION_NAME_TAKEN";

	public static final String INVALID_GROUP = "INVALID_GROUP";

	public static final String SELECTION_TYPE_LOCKED = "SELECTION_TYPE_LOCKED";

	public static final String TEXT_GROUP_HAS_NO_OPTIONS = "TEXT_GROUP_HAS_NO_OPTIONS";

	private final CustomizationGroupRepository groups;

	private final CustomizationOptionRepository options;

	private final AuditLog audit;

	public CustomizationAdminService(CustomizationGroupRepository groups, CustomizationOptionRepository options,
			AuditLog audit) {
		this.groups = groups;
		this.options = options;
		this.audit = audit;
	}

	@Transactional(readOnly = true)
	public List<AdminGroupResponse> all() {
		List<CustomizationGroup> all = groups.findAllByOrderByCodeAsc();
		Map<UUID, List<CustomizationOption>> byGroup = options
			.findByGroupIds(all.stream().map(CustomizationGroup::getId).toList())
			.stream()
			.collect(Collectors.groupingBy(o -> o.getGroup().getId()));
		return all.stream().map(g -> AdminGroupResponse.from(g, byGroup.getOrDefault(g.getId(), List.of()))).toList();
	}

	@Transactional
	public AdminGroupResponse createGroup(UUID actor, GroupWrite write) {
		checkShape(write);
		if (groups.existsByCode(write.code())) {
			throw codeTaken();
		}
		CustomizationGroup group = saveGroup(new CustomizationGroup(write.code(), write.name().strip(),
				write.selectionType(), write.minSelections(), write.maxSelections()));
		audit.admin(actor, "CUSTOMIZATION_GROUP_CREATED", "customization_group", group.getId(), null,
				groupDetails(group));
		return AdminGroupResponse.from(group, List.of());
	}

	@Transactional
	public AdminGroupResponse updateGroup(UUID actor, UUID id, GroupWrite write) {
		CustomizationGroup group = groups.findById(id)
			.orElseThrow(() -> ApiException.notFound(GROUP_NOT_FOUND, "Customization group not found."));
		checkShape(write);
		if (groups.existsByCodeAndIdNot(write.code(), id)) {
			throw codeTaken();
		}
		if (group.getSelectionType() != write.selectionType() && options.existsByGroupId(id)) {
			throw ApiException.conflict(SELECTION_TYPE_LOCKED,
					"The selection type cannot change once the group has options.");
		}
		Map<String, Object> before = groupDetails(group);
		group.replace(write.code(), write.name().strip(), write.selectionType(), write.minSelections(),
				write.maxSelections());
		saveGroup(group);
		Map<String, Object> after = groupDetails(group);
		if (!before.equals(after)) {
			audit.admin(actor, "CUSTOMIZATION_GROUP_UPDATED", "customization_group", id, null,
					Map.of("from", before, "to", after));
		}
		return AdminGroupResponse.from(group, options.findByGroupIds(List.of(id)));
	}

	@Transactional
	public AdminOptionResponse createOption(UUID actor, UUID groupId, OptionWrite write) {
		CustomizationGroup group = groups.findById(groupId)
			.orElseThrow(() -> ApiException.notFound(GROUP_NOT_FOUND, "Customization group not found."));
		if (group.getSelectionType() == SelectionType.TEXT) {
			throw ApiException.unprocessable(TEXT_GROUP_HAS_NO_OPTIONS, "TEXT groups take a message, not options.");
		}
		String name = write.name().strip();
		if (options.existsByGroupIdAndName(groupId, name)) {
			throw nameTaken();
		}
		CustomizationOption option = saveOption(
				new CustomizationOption(group, name, write.priceDelta(), write.activeOrDefault()));
		audit.admin(actor, "CUSTOMIZATION_OPTION_CREATED", "customization_option", option.getId(), null,
				optionDetails(option));
		return AdminOptionResponse.from(option);
	}

	@Transactional
	public AdminOptionResponse updateOption(UUID actor, UUID optionId, OptionWrite write) {
		CustomizationOption option = options.findWithGroup(optionId)
			.orElseThrow(() -> ApiException.notFound(OPTION_NOT_FOUND, "Customization option not found."));
		String name = write.name().strip();
		if (options.existsByGroupIdAndNameAndIdNot(option.getGroup().getId(), name, optionId)) {
			throw nameTaken();
		}
		Map<String, Object> before = optionDetails(option);
		option.replace(name, write.priceDelta(), write.activeOrDefault());
		saveOption(option);
		Map<String, Object> after = optionDetails(option);
		if (!before.equals(after)) {
			audit.admin(actor, "CUSTOMIZATION_OPTION_UPDATED", "customization_option", optionId, null,
					Map.of("from", before, "to", after));
		}
		return AdminOptionResponse.from(option);
	}

	private static void checkShape(GroupWrite write) {
		if (write.maxSelections() < write.minSelections()) {
			throw invalid("maxSelections must be at least minSelections.");
		}
		if (write.selectionType() != SelectionType.MULTIPLE && write.maxSelections() != 1) {
			throw invalid(write.selectionType() + " groups allow at most one selection: maxSelections must be 1.");
		}
	}

	private CustomizationGroup saveGroup(CustomizationGroup group) {
		try {
			return groups.saveAndFlush(group);
		}
		catch (DataIntegrityViolationException ex) {
			throw codeTaken();
		}
	}

	private CustomizationOption saveOption(CustomizationOption option) {
		try {
			return options.saveAndFlush(option);
		}
		catch (DataIntegrityViolationException ex) {
			throw nameTaken();
		}
	}

	private static Map<String, Object> groupDetails(CustomizationGroup g) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("code", g.getCode());
		details.put("name", g.getName());
		details.put("selectionType", g.getSelectionType().name());
		details.put("minSelections", g.getMinSelections());
		details.put("maxSelections", g.getMaxSelections());
		return details;
	}

	private static Map<String, Object> optionDetails(CustomizationOption o) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("groupId", o.getGroup().getId().toString());
		details.put("name", o.getName());
		details.put("priceDelta", o.getPriceDelta().amount().toPlainString());
		details.put("active", o.isActive());
		return details;
	}

	private static ApiException invalid(String detail) {
		return ApiException.unprocessable(INVALID_GROUP, detail);
	}

	private static ApiException codeTaken() {
		return ApiException.conflict(GROUP_CODE_TAKEN, "Another customization group already uses this code.");
	}

	private static ApiException nameTaken() {
		return ApiException.conflict(OPTION_NAME_TAKEN, "This group already has an option with that name.");
	}

}
