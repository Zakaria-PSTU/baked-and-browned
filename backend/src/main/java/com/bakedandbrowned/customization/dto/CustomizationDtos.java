package com.bakedandbrowned.customization.dto;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.bakedandbrowned.common.value.Money;
import com.bakedandbrowned.customization.entity.CustomizationGroup;
import com.bakedandbrowned.customization.entity.CustomizationOption;
import com.bakedandbrowned.customization.entity.SelectionType;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** API shapes of customization administration (contract tag Admin). */
public final class CustomizationDtos {

	private CustomizationDtos() {
	}

	/** Contract {@code AdminOption}. */
	public record AdminOptionResponse(UUID id, UUID groupId, String name, Money priceDelta, boolean active,
			int extraProductionUnits, @Nullable Integer minLeadHours) {

		public static AdminOptionResponse from(CustomizationOption o) {
			return new AdminOptionResponse(o.getId(), o.getGroup().getId(), o.getName(), o.getPriceDelta(), o.isActive(),
					o.getExtraProductionUnits(), o.getMinLeadHours());
		}

	}

	/** Contract {@code AdminCustomizationGroup}: all options, including inactive ones. */
	public record AdminGroupResponse(UUID id, String code, String name, SelectionType selectionType,
			int minSelections, int maxSelections, List<AdminOptionResponse> options) {

		public static AdminGroupResponse from(CustomizationGroup g, List<CustomizationOption> options) {
			return new AdminGroupResponse(g.getId(), g.getCode(), g.getName(), g.getSelectionType(), g.getMinSelections(),
					g.getMaxSelections(), options.stream().map(AdminOptionResponse::from).toList());
		}

	}

	/** Contract {@code CustomizationGroupWrite}. */
	public record GroupWrite(@NotNull @Pattern(regexp = "^[A-Z0-9_]{2,80}$") String code,
			@NotBlank @Size(max = 100) String name, @NotNull SelectionType selectionType,
			@NotNull @Min(0) Integer minSelections, @NotNull @Min(1) Integer maxSelections) {
	}

	/** Contract {@code OptionWrite}. */
	public record OptionWrite(@NotBlank @Size(max = 120) String name, @NotNull Money priceDelta,
			@Nullable Boolean active, @Nullable @Min(0) @Max(100) Integer extraProductionUnits,
			@Nullable @Min(0) @Max(720) Integer minLeadHours) {

		public boolean activeOrDefault() {
			return active == null || active;
		}

		/** Absent means none: the write replaces every field. */
		public int extraProductionUnitsOrZero() {
			return extraProductionUnits == null ? 0 : extraProductionUnits;
		}

	}

	/** Contract {@code DisallowedCombination}: two options of the product that cannot be chosen together. */
	public record DisallowedCombination(@NotNull @Size(min = 2, max = 2) List<@NotNull UUID> optionIds) {
	}

	/** Contract {@code ProductCustomizationAssignment}. */
	public record AssignmentWrite(@NotNull UUID groupId, @NotNull Boolean required,
			@NotNull List<@NotNull UUID> allowedOptionIds, @Nullable @Min(1) @Max(250) Integer textMaxLength) {
	}

}
