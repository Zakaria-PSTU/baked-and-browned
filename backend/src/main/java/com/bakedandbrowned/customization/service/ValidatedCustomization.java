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
 */
public record ValidatedCustomization(List<Choice> choices, Money optionsTotal) {

	public ValidatedCustomization {
		choices = List.copyOf(choices);
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

}
