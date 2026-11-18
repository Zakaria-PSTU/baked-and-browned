package com.bakedandbrowned.customization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.bakedandbrowned.catalog.CatalogIntegrationTest;
import com.bakedandbrowned.common.value.Money;
import com.bakedandbrowned.common.web.ApiException;
import com.bakedandbrowned.customization.service.ProductCustomizationService;
import com.bakedandbrowned.customization.service.SelectionValidator;
import com.bakedandbrowned.customization.service.ValidatedCustomization;
import com.bakedandbrowned.customization.service.ValidatedCustomization.Choice;

/** The validator against rules loaded from the database (what quote and checkout will call). */
class SelectionValidationIntegrationTests extends CatalogIntegrationTest {

	@Autowired
	private ProductCustomizationService customizations;

	@Test
	void validatesAgainstTheProductsCurrentRules() {
		UUID product = product(category("Cakes", "cakes", 1, true), "Message Cake", "message-cake", "2500.00",
				"ACTIVE");
		UUID otherProduct = product(category("Cookies", "cookies", 2, true), "Cookies", "cookies-box", "600.00",
				"ACTIVE");
		UUID size = group("CAKE_SIZE", "Cake size", "SINGLE", 1, 1);
		UUID half = option(size, "Half kg", "0.00", true);
		UUID one = option(size, "1 kg", "900.00", true);
		UUID two = option(size, "2 kg", "2200.00", false); // allowed but inactive
		UUID party = option(size, "Party", "5000.00", true); // active but not allowed for this product
		UUID message = group("CAKE_MESSAGE", "Message", "TEXT", 0, 1);
		assign(product, size, true, null, half, one, two);
		assign(product, message, false, 12);
		assign(otherProduct, size, false, null, party);

		ValidatedCustomization ok = customizations.validateSelection(product, List.of(one),
				Map.of(message, " Happy Bday! "));
		assertThat(ok.optionsTotal()).isEqualTo(Money.of("900.00"));
		assertThat(ok.choices()).extracting(Choice::groupName).containsExactly("Message", "Cake size");
		assertThat(ok.choices().get(0).textValue()).isEqualTo("Happy Bday!");

		assertCode(() -> customizations.validateSelection(product, List.of(two), Map.of()),
				SelectionValidator.OPTION_INACTIVE);
		assertCode(() -> customizations.validateSelection(product, List.of(party), Map.of()),
				SelectionValidator.OPTION_NOT_ALLOWED);
		assertCode(() -> customizations.validateSelection(product, List.of(), Map.of()),
				SelectionValidator.SELECTION_COUNT);
		// The product-specific limit (12), not the database's 250.
		assertCode(() -> customizations.validateSelection(product, List.of(half), Map.of(message, "x".repeat(13))),
				SelectionValidator.TEXT_TOO_LONG);
		// The same group is optional for the other product, with only its own allowed option.
		assertThat(customizations.validateSelection(otherProduct, List.of(), Map.of()).choices()).isEmpty();
		assertCode(() -> customizations.validateSelection(otherProduct, List.of(half), Map.of()),
				SelectionValidator.OPTION_NOT_ALLOWED);
	}

	private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
		assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(code));
	}

}
