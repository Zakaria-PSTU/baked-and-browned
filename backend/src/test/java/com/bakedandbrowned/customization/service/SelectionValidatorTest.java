package com.bakedandbrowned.customization.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.bakedandbrowned.common.value.Money;
import com.bakedandbrowned.common.web.ApiException;
import com.bakedandbrowned.customization.entity.SelectionType;
import com.bakedandbrowned.customization.service.CustomizationRules.GroupRule;
import com.bakedandbrowned.customization.service.CustomizationRules.OptionRule;
import com.bakedandbrowned.customization.service.ValidatedCustomization.Choice;

class SelectionValidatorTest {

	// ------------------------------------------------------------------ fixture: a brownie box

	private static final OptionRule SIX = option("6 pieces", "0.00", true);

	private static final OptionRule TWELVE = option("12 pieces", "600.00", true);

	private static final OptionRule RETIRED = option("24 pieces", "1500.00", false);

	private static final OptionRule WALNUT = option("Walnuts", "80.00", true);

	private static final OptionRule SPRINKLES = option("Sprinkles", "40.00", true);

	private static final OptionRule CARAMEL = option("Caramel drizzle", "120.00", true);

	private static final GroupRule SIZE = group("BOX_SIZE", "Box size", SelectionType.SINGLE, true, 1, 1, null, TWELVE,
			SIX, RETIRED);

	private static final GroupRule TOPPINGS = group("TOPPINGS", "Toppings", SelectionType.MULTIPLE, false, 0, 2, null,
			CARAMEL, SPRINKLES, WALNUT);

	private static final GroupRule NOTE = group("GIFT_NOTE", "Gift note", SelectionType.TEXT, false, 0, 1, 20);

	private static final CustomizationRules BOX = new CustomizationRules(List.of(SIZE, NOTE, TOPPINGS));

	// ------------------------------------------------------------------ effective selection rule

	@ParameterizedTest(name = "required={0} min={1} max={2} count={3} -> {4}")
	@CsvSource({
			// required: between max(min, 1) and max
			"true, 0, 1, 0, false", "true, 0, 1, 1, true", "true, 0, 3, 3, true", "true, 0, 3, 4, false",
			"true, 2, 3, 1, false", "true, 2, 3, 2, true",
			// optional: zero, or between max(min, 1) and max
			"false, 0, 1, 0, true", "false, 0, 1, 1, true", "false, 0, 1, 2, false", "false, 2, 3, 0, true",
			"false, 2, 3, 1, false", "false, 2, 3, 3, true", "false, 2, 3, 4, false" })
	void effectiveSelectionRule(boolean required, int min, int max, int count, boolean allowed) {
		assertThat(SelectionValidator.countAllowed(required, min, max, count)).isEqualTo(allowed);
	}

	// ------------------------------------------------------------------ options

	@Nested
	class Options {

		@Test
		void validChoicesArePricedAndOrderedByGroupThenOption() {
			ValidatedCustomization result = validate(List.of(WALNUT.id(), TWELVE.id(), CARAMEL.id()), Map.of());

			assertThat(result.choices()).extracting(Choice::optionName)
				.containsExactly("12 pieces", "Caramel drizzle", "Walnuts");
			assertThat(result.choices()).extracting(Choice::groupName)
				.containsExactly("Box size", "Toppings", "Toppings");
			assertThat(result.optionsTotal()).isEqualTo(Money.of("800.00"));
		}

		@Test
		void optionalGroupsMayBeLeftEmpty() {
			ValidatedCustomization result = validate(List.of(SIX.id()), Map.of());
			assertThat(result.choices()).singleElement().extracting(Choice::optionId).isEqualTo(SIX.id());
			assertThat(result.optionsTotal()).isEqualTo(Money.ZERO);
		}

		@Test
		void requiredGroupsMustBeChosen() {
			assertRejected(() -> validate(List.of(WALNUT.id()), Map.of()), SelectionValidator.SELECTION_COUNT,
					"Choose 1 for \"Box size\".");
		}

		@Test
		void singleGroupsTakeOneOption() {
			assertRejected(() -> validate(List.of(SIX.id(), TWELVE.id()), Map.of()), SelectionValidator.SELECTION_COUNT,
					"Box size");
		}

		@Test
		void multipleGroupsRespectTheirMaximum() {
			assertRejected(() -> validate(List.of(SIX.id(), WALNUT.id(), SPRINKLES.id(), CARAMEL.id()), Map.of()),
					SelectionValidator.SELECTION_COUNT, "Choose none or 1 to 2 for \"Toppings\".");
		}

		@Test
		void duplicatesAreRejectedNotCollapsed() {
			assertRejected(() -> validate(List.of(SIX.id(), SIX.id()), Map.of()), SelectionValidator.DUPLICATE_OPTION,
					"more than once");
		}

		@Test
		void optionsNotAllowedForTheProductAreRejected() {
			assertRejected(() -> validate(List.of(SIX.id(), UUID.randomUUID()), Map.of()),
					SelectionValidator.OPTION_NOT_ALLOWED, "not available for this product");
		}

		@Test
		void inactiveOptionsAreRejectedEvenIfAllowed() {
			assertRejected(() -> validate(List.of(RETIRED.id()), Map.of()), SelectionValidator.OPTION_INACTIVE,
					"\"Box size\" is no longer available");
		}

		@Test
		void productsWithoutCustomizationsAcceptNothing() {
			CustomizationRules none = new CustomizationRules(List.of());
			assertThat(SelectionValidator.validate(none, List.of(), Map.of()).choices()).isEmpty();
			assertRejected(() -> SelectionValidator.validate(none, List.of(SIX.id()), Map.of()),
					SelectionValidator.OPTION_NOT_ALLOWED, "not available");
		}

	}

	// ------------------------------------------------------------------ text

	@Nested
	class Texts {

		@Test
		void messagesAreStrippedAndFree() {
			ValidatedCustomization result = validate(List.of(SIX.id()), Map.of(NOTE.id(), "  Happy birthday!  "));
			assertThat(result.choices()).filteredOn(c -> c.textValue() != null)
				.singleElement()
				.satisfies(c -> {
					assertThat(c.textValue()).isEqualTo("Happy birthday!");
					assertThat(c.groupName()).isEqualTo("Gift note");
					assertThat(c.optionId()).isNull();
					assertThat(c.priceDelta()).isEqualTo(Money.ZERO);
				});
		}

		@Test
		void blankMessagesCountAsNone() {
			assertThat(validate(List.of(SIX.id()), Map.of(NOTE.id(), " \n ")).choices()).hasSize(1);
		}

		@Test
		void requiredMessagesMustBeGiven() {
			GroupRule message = group("CAKE_MESSAGE", "Cake message", SelectionType.TEXT, true, 0, 1, 50);
			CustomizationRules cake = new CustomizationRules(List.of(message));
			assertRejected(() -> SelectionValidator.validate(cake, List.of(), Map.of(message.id(), "  ")),
					SelectionValidator.SELECTION_COUNT, "Please add a message for \"Cake message\".");
		}

		@Test
		void lengthIsCountedInCharactersLikePostgres() {
			// 20 emoji: 20 characters for PostgreSQL, 40 UTF-16 units for String.length().
			String twentyEmoji = "🎂".repeat(20);
			assertThat(validate(List.of(SIX.id()), Map.of(NOTE.id(), twentyEmoji)).choices()).hasSize(2);
			assertRejected(() -> validate(List.of(SIX.id()), Map.of(NOTE.id(), twentyEmoji + "!")),
					SelectionValidator.TEXT_TOO_LONG, "at most 20 characters");
		}

		@Test
		void combiningSequencesAreNormalizedBeforeCounting() {
			// "é" typed as e + combining acute is one character after NFC.
			String decomposed = "é".repeat(20);
			ValidatedCustomization result = validate(List.of(SIX.id()), Map.of(NOTE.id(), decomposed));
			assertThat(result.choices().get(1).textValue()).isEqualTo("é".repeat(20));
		}

		@Test
		void emojiJoinSequencesAreAllowed() {
			String family = "👩‍👧"; // woman, ZWJ, girl
			assertThat(validate(List.of(SIX.id()), Map.of(NOTE.id(), "Love " + family)).choices()).hasSize(2);
		}

		@Test
		void controlAndDirectionOverrideCharactersAreRejected() {
			for (String bad : List.of("Hi\u0007", "Line\nbreak", "Tab\there", "Paid ‮dias", "⁦x⁩")) {
				assertRejected(() -> validate(List.of(SIX.id()), Map.of(NOTE.id(), bad)), SelectionValidator.TEXT_INVALID,
						"cannot print");
			}
		}

		@Test
		void messagesOnlyGoToTheProductsTextGroups() {
			assertRejected(() -> validate(List.of(SIX.id()), Map.of(SIZE.id(), "Hello")),
					SelectionValidator.TEXT_NOT_ALLOWED, "does not take one");
			assertRejected(() -> validate(List.of(SIX.id()), Map.of(UUID.randomUUID(), "Hello")),
					SelectionValidator.TEXT_NOT_ALLOWED, "does not take one");
		}

		@Test
		void errorDetailsNeverEchoTheCustomersText() {
			Map<UUID, String> texts = new LinkedHashMap<>();
			texts.put(NOTE.id(), "<script>alert('x')</script> and more and more");
			assertThatThrownBy(() -> validate(List.of(SIX.id()), texts)).isInstanceOfSatisfying(ApiException.class,
					ex -> assertThat(ex.getMessage()).doesNotContain("script").doesNotContain("alert"));
		}

	}

	@Test
	void chosenOptionsAddTheirKitchenCostsAndTheStrictestNoticeWins() {
		OptionRule small = new OptionRule(UUID.randomUUID(), "1 kg", Money.of("0.00"), true, 0, null);
		OptionRule large = new OptionRule(UUID.randomUUID(), "3 kg", Money.of("2500.00"), true, 4, 48);
		OptionRule painted = new OptionRule(UUID.randomUUID(), "Hand-painted design", Money.of("1500.00"), true, 1, 72);
		OptionRule fruit = new OptionRule(UUID.randomUUID(), "Fresh fruit", Money.of("300.00"), true, 0, 36);
		GroupRule size = group("CAKE_SIZE", "Cake size", SelectionType.SINGLE, true, 1, 1, null, small, large);
		GroupRule finish = group("CAKE_FINISH", "Finish", SelectionType.MULTIPLE, false, 0, 2, null, painted, fruit);
		CustomizationRules cake = new CustomizationRules(List.of(size, finish));

		ValidatedCustomization plain = SelectionValidator.validate(cake, List.of(small.id()), Map.of());
		assertThat(plain.extraProductionUnits()).isZero();
		assertThat(plain.strictestLead()).isNull();

		ValidatedCustomization fancy = SelectionValidator.validate(cake, List.of(fruit.id(), large.id(), painted.id()),
				Map.of());
		assertThat(fancy.extraProductionUnits()).isEqualTo(5);
		assertThat(fancy.strictestLead()).isEqualTo(new ValidatedCustomization.OptionLead("Hand-painted design", 72));
		assertThat(fancy.optionIds()).containsExactlyInAnyOrder(fruit.id(), large.id(), painted.id());
		assertThat(fancy.optionsTotal()).isEqualTo(Money.of("4300.00"));
	}

	// ------------------------------------------------------------------ helpers

	private static ValidatedCustomization validate(List<UUID> options, Map<UUID, String> texts) {
		return SelectionValidator.validate(BOX, options, texts);
	}

	private static void assertRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code,
			String detailFragment) {
		assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, ex -> {
			assertThat(ex.status().value()).isEqualTo(422);
			assertThat(ex.code()).isEqualTo(code);
			assertThat(ex.getMessage()).contains(detailFragment);
		});
	}

	private static OptionRule option(String name, String delta, boolean active) {
		return new OptionRule(UUID.randomUUID(), name, Money.of(delta), active);
	}

	/** Options in display order are passed sorted by name, as the loader returns them. */
	private static GroupRule group(String code, String name, SelectionType type, boolean required, int min, int max,
			Integer textMax, OptionRule... options) {
		Map<UUID, OptionRule> byId = new LinkedHashMap<>();
		java.util.Arrays.stream(options)
			.sorted(java.util.Comparator.comparing(OptionRule::name))
			.forEach(o -> byId.put(o.id(), o));
		return new GroupRule(UUID.randomUUID(), code, name, type, required, min, max, textMax, byId);
	}

}
