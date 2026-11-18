package com.bakedandbrowned.customization.service;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.bakedandbrowned.common.value.Money;

/**
 * A validated set of choices for one product, ready to price and to snapshot on an order item
 * (contract {@code OrderItemCustomization}). Names and deltas are copied as they are now; later
 * catalog changes never alter a placed order.
 *
 * @param optionsTotal sum of the chosen options' price deltas (text messages are free)
 * @param extraProductionUnits kitchen capacity the chosen options add per item
 * @param strictestLead the chosen option needing the most notice, if any option needs any
 */
public record ValidatedCustomization(List<Choice> choices, Money optionsTotal, int extraProductionUnits,
		@Nullable OptionLead strictestLead) {

	public ValidatedCustomization {
		choices = List.copyOf(choices);
		if (extraProductionUnits < 0) {
			throw new IllegalArgumentException("extraProductionUnits must not be negative");
		}
	}

	/** Choices with no kitchen cost beyond the product. */
	public ValidatedCustomization(List<Choice> choices, Money optionsTotal) {
		this(choices, optionsTotal, 0, null);
	}

	/** The chosen options, by id (what their ingredients are looked up by). */
	public List<UUID> optionIds() {
		return choices.stream().map(Choice::optionId).filter(java.util.Objects::nonNull).toList();
	}

	/** Either an option ({@code optionId}, {@code optionName}) or a message ({@code textValue}). */
	public record Choice(UUID groupId, String groupName, @Nullable UUID optionId, @Nullable String optionName,
			@Nullable String textValue, Money priceDelta) {

		static Choice option(CustomizationRules.GroupRule group, CustomizationRules.OptionRule option) {
			return new Choice(group.id(), group.name(), option.id(), option.name(), null, option.priceDelta());
		}

		static Choice text(CustomizationRules.GroupRule group, String text) {
			return new Choice(group.id(), group.name(), null, null, text, Money.ZERO);
		}

	}

	/** An option's notice requirement, named for the customer-facing error. */
	public record OptionLead(String optionName, int hours) {
	}

}
