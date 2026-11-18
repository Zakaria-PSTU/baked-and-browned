package com.bakedandbrowned.customization.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.bakedandbrowned.common.value.Money;
import com.bakedandbrowned.common.web.ApiException;
import com.bakedandbrowned.customization.entity.SelectionType;
import com.bakedandbrowned.customization.service.CustomizationRules.GroupRule;
import com.bakedandbrowned.customization.service.CustomizationRules.OptionRule;

/**
 * Server-side validation of a cart line's customizations (business rules, Customization). Pure:
 * no I/O, so every rule is unit-tested directly. Failures are 422 with a stable code and a detail
 * that names the group (admin-defined display text) but never echoes customer input.
 * <p>
 * Effective selection rule for a product/group pair, with {@code lower = max(minSelections, 1)}:
 * <ul>
 * <li>required: between {@code lower} and {@code maxSelections} selections;</li>
 * <li>optional: zero, or between {@code lower} and {@code maxSelections}.</li>
 * </ul>
 * A TEXT group counts one selection when its message is non-blank.
 */
public final class SelectionValidator {

	public static final String DUPLICATE_OPTION = "DUPLICATE_OPTION";

	public static final String OPTION_NOT_ALLOWED = "OPTION_NOT_ALLOWED";

	public static final String OPTION_INACTIVE = "OPTION_INACTIVE";

	public static final String TEXT_NOT_ALLOWED = "TEXT_NOT_ALLOWED";

	public static final String TEXT_TOO_LONG = "TEXT_TOO_LONG";

	public static final String TEXT_INVALID = "TEXT_INVALID";

	public static final String SELECTION_COUNT = "SELECTION_COUNT";

	private SelectionValidator() {
	}

	/** Whether {@code count} selections satisfy the effective rule. */
	public static boolean countAllowed(boolean required, int minSelections, int maxSelections, int count) {
		int lower = Math.max(minSelections, 1);
		boolean inRange = count >= lower && count <= maxSelections;
		return required ? inRange : count == 0 || inRange;
	}

	/**
	 * @param selectedOptionIds as sent by the client (duplicates are rejected, not collapsed)
	 * @param customTexts group id to message, as sent by the client
	 */
	public static ValidatedCustomization validate(CustomizationRules rules, List<UUID> selectedOptionIds,
			Map<UUID, String> customTexts) {
		Map<UUID, GroupRule> groupOfOption = new HashMap<>();
		Map<UUID, GroupRule> groupsById = new HashMap<>();
		for (GroupRule group : rules.groups()) {
			groupsById.put(group.id(), group);
			group.allowedOptions().keySet().forEach(option -> groupOfOption.put(option, group));
		}

		Set<UUID> selected = new HashSet<>();
		for (UUID optionId : selectedOptionIds) {
			if (!selected.add(optionId)) {
				throw fail(DUPLICATE_OPTION, "An option was selected more than once.");
			}
			GroupRule group = groupOfOption.get(optionId);
			if (group == null) {
				throw fail(OPTION_NOT_ALLOWED, "A selected option is not available for this product.");
			}
			if (!group.allowedOptions().get(optionId).active()) {
				throw fail(OPTION_INACTIVE, "An option in \"" + group.name() + "\" is no longer available.");
			}
		}

		Map<UUID, String> texts = new HashMap<>();
		customTexts.forEach((groupId, raw) -> {
			GroupRule group = groupsById.get(groupId);
			if (group == null || group.type() != SelectionType.TEXT) {
				throw fail(TEXT_NOT_ALLOWED, "A message was given for something that does not take one.");
			}
			String text = normalize(raw);
			if (text != null) {
				checkText(group, text);
				texts.put(groupId, text);
			}
		});

		List<ValidatedCustomization.Choice> choices = new ArrayList<>();
		Money total = Money.ZERO;
		for (GroupRule group : rules.groups()) {
			int count;
			if (group.type() == SelectionType.TEXT) {
				String text = texts.get(group.id());
				count = text == null ? 0 : 1;
				if (text != null) {
					choices.add(ValidatedCustomization.Choice.text(group, text));
				}
			}
			else {
				count = 0;
				for (OptionRule option : group.allowedOptions().values()) {
					if (selected.contains(option.id())) {
						count++;
						choices.add(ValidatedCustomization.Choice.option(group, option));
						total = total.plus(option.priceDelta());
					}
				}
			}
			if (!countAllowed(group.required(), group.minSelections(), group.maxSelections(), count)) {
				throw fail(SELECTION_COUNT, describeCount(group));
			}
		}
		return new ValidatedCustomization(choices, total);
	}

	/** NFC-normalized and stripped; blank means "no message". */
	private static @Nullable String normalize(@Nullable String raw) {
		if (raw == null) {
			return null;
		}
		String text = Normalizer.normalize(raw, Normalizer.Form.NFC).strip();
		return text.isEmpty() ? null : text;
	}

	private static void checkText(GroupRule group, String text) {
		boolean invalid = text.codePoints().anyMatch(cp -> Character.getType(cp) == Character.CONTROL || isBidiControl(cp));
		if (invalid) {
			throw fail(TEXT_INVALID, "The message for \"" + group.name() + "\" contains characters we cannot print.");
		}
		// Characters as PostgreSQL counts them (code points), not UTF-16 units.
		int limit = group.textMaxLength() == null ? 250 : group.textMaxLength();
		if (text.codePointCount(0, text.length()) > limit) {
			throw fail(TEXT_TOO_LONG, "The message for \"" + group.name() + "\" can be at most " + limit + " characters.");
		}
	}

	/** Explicit direction overrides/isolates, which can make an order's text display deceptively. */
	private static boolean isBidiControl(int cp) {
		return (cp >= 0x202A && cp <= 0x202E) || (cp >= 0x2066 && cp <= 0x2069);
	}

	private static String describeCount(GroupRule group) {
		int lower = Math.max(group.minSelections(), 1);
		String range = lower == group.maxSelections() ? String.valueOf(lower) : lower + " to " + group.maxSelections();
		if (group.type() == SelectionType.TEXT) {
			return "Please add a message for \"" + group.name() + "\".";
		}
		return (group.required() ? "Choose " : "Choose none or ") + range + " for \"" + group.name() + "\".";
	}

	private static ApiException fail(String code, String detail) {
		return ApiException.unprocessable(code, detail);
	}

}
