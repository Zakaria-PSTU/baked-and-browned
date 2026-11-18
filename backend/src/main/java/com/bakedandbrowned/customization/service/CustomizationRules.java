package com.bakedandbrowned.customization.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.bakedandbrowned.common.value.Money;
import com.bakedandbrowned.customization.entity.SelectionType;

/**
 * Everything needed to validate one product's customization choices: its assigned groups (ordered
 * by code) with the options explicitly allowed for this product, including inactive ones (so
 * "inactive" can be told apart from "not allowed"), and the option pairs it disallows together
 * (ADR-0020).
 */
public record CustomizationRules(List<GroupRule> groups, List<OptionPair> disallowedPairs) {

	public CustomizationRules {
		groups = List.copyOf(groups);
		disallowedPairs = List.copyOf(disallowedPairs);
	}

	/** Rules without disallowed combinations. */
	public CustomizationRules(List<GroupRule> groups) {
		this(groups, List.of());
	}

	/** Two options of one product that cannot be chosen together; the order carries no meaning. */
	public record OptionPair(UUID first, UUID second) {

		public OptionPair {
			Objects.requireNonNull(first, "first");
			Objects.requireNonNull(second, "second");
		}

	}

	/**
	 * @param textMaxLength effective limit for TEXT groups (product's own or the database's 250);
	 * null for other types
	 * @param allowedOptions allowed options by id, in display order (by name)
	 */
	public record GroupRule(UUID id, String code, String name, SelectionType type, boolean required,
			int minSelections, int maxSelections, @Nullable Integer textMaxLength, Map<UUID, OptionRule> allowedOptions) {

		public GroupRule {
			Objects.requireNonNull(id, "id");
			Objects.requireNonNull(type, "type");
			allowedOptions = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(allowedOptions));
		}

	}

	/**
	 * @param extraProductionUnits kitchen capacity added per item
	 * @param minLeadHours notice the option needs, if any; the stricter of this and the product's applies
	 */
	public record OptionRule(UUID id, String name, Money priceDelta, boolean active, int extraProductionUnits,
			@Nullable Integer minLeadHours) {

		/** An option with no kitchen cost beyond its product. */
		public OptionRule(UUID id, String name, Money priceDelta, boolean active) {
			this(id, name, priceDelta, active, 0, null);
		}

	}

}
