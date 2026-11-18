package com.bakedandbrowned.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Contract tag {@code Catalog}: anonymous reads of the public catalog. */
class PublicCatalogIntegrationTests extends CatalogIntegrationTest {

	@Test
	void categoriesAreActiveOnlyInSortOrderThenName() throws Exception {
		category("Pastries", "pastries", 2, true);
		category("Cookies", "cookies", 1, true);
		category("Brownies", "brownies", 1, true);
		category("Seasonal", "seasonal", 0, false);

		mockMvc.perform(get("/api/v1/categories"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[*].slug", contains("brownies", "cookies", "pastries")))
			.andExpect(jsonPath("$[0].sortOrder").doesNotExist())
			.andExpect(jsonPath("$[0].active").doesNotExist());
	}

	@Test
	void productListShowsOnlyActiveProductsOfActiveCategories() throws Exception {
		UUID brownies = category("Brownies", "brownies", 1, true);
		UUID hidden = category("Seasonal", "seasonal", 2, false);
		product(brownies, "Fudge Brownie Box", "fudge-brownie-box", "1250.00", "ACTIVE");
		product(brownies, "Walnut Brownie Box", "walnut-brownie-box", "1350.00", "DRAFT");
		product(brownies, "Old Brownie", "old-brownie", "900.00", "ARCHIVED");
		product(hidden, "Pumpkin Bar", "pumpkin-bar", "400.00", "ACTIVE");

		mockMvc.perform(get("/api/v1/products"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content[*].slug", contains("fudge-brownie-box")))
			.andExpect(jsonPath("$.totalElements").value(1))
			.andExpect(jsonPath("$.content[0].currency").value("BDT"))
			.andExpect(jsonPath("$.content[0].customizationGroups", empty()))
			.andExpect(jsonPath("$.content[0].status").doesNotExist())
			.andExpect(jsonPath("$.content[0].productionUnits").doesNotExist());
	}

	@Test
	void moneyIsSerializedWithTwoDecimals() throws Exception {
		product(category("Brownies", "brownies", 1, true), "Fudge Brownie Box", "fudge-brownie-box", "1250", "ACTIVE");

		String body = mockMvc.perform(get("/api/v1/products/fudge-brownie-box"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(body).contains("\"basePrice\":1250.00");
	}

	@Test
	void filtersByCategorySlugAndSearchesNamesLiterally() throws Exception {
		UUID brownies = category("Brownies", "brownies", 1, true);
		UUID cookies = category("Cookies", "cookies", 2, true);
		product(brownies, "Fudge Brownie Box", "fudge-brownie-box", "1250.00", "ACTIVE");
		product(brownies, "100% Cocoa Brownie", "cocoa-brownie", "1400.00", "ACTIVE");
		product(cookies, "Choc Chip Cookies", "choc-chip-cookies", "600.00", "ACTIVE");

		mockMvc.perform(get("/api/v1/products").param("category", "cookies"))
			.andExpect(jsonPath("$.content[*].slug", contains("choc-chip-cookies")));
		mockMvc.perform(get("/api/v1/products").param("category", "no-such-category"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(0));
		mockMvc.perform(get("/api/v1/products").param("q", "BROWNIE"))
			.andExpect(jsonPath("$.content[*].slug", contains("cocoa-brownie", "fudge-brownie-box")));
		// "%" is matched literally, not as a wildcard.
		mockMvc.perform(get("/api/v1/products").param("q", "100%"))
			.andExpect(jsonPath("$.content[*].slug", contains("cocoa-brownie")));
		mockMvc.perform(get("/api/v1/products").param("q", "%"))
			.andExpect(jsonPath("$.content[*].slug", contains("cocoa-brownie")));
		mockMvc.perform(get("/api/v1/products").param("q", "_"))
			.andExpect(jsonPath("$.totalElements").value(0));
	}

	@Test
	void pagesAreStableAndBounded() throws Exception {
		UUID brownies = category("Brownies", "brownies", 1, true);
		for (String name : new String[] { "E", "C", "A", "D", "B" }) {
			product(brownies, "Box " + name, "box-" + name.toLowerCase(), "100.00", "ACTIVE");
		}

		mockMvc.perform(get("/api/v1/products").param("page", "1").param("size", "2"))
			.andExpect(jsonPath("$.content[*].name", contains("Box C", "Box D")))
			.andExpect(jsonPath("$.page").value(1))
			.andExpect(jsonPath("$.size").value(2))
			.andExpect(jsonPath("$.totalElements").value(5));

		for (String[] bad : new String[][] { { "size", "101" }, { "size", "0" }, { "page", "-1" },
				{ "q", "x".repeat(101) } }) {
			mockMvc.perform(get("/api/v1/products").param(bad[0], bad[1]))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		}
	}

	@Test
	void productDetailOffersOnlyActiveAllowedOptions() throws Exception {
		UUID product = product(category("Brownies", "brownies", 1, true), "Fudge Brownie Box", "fudge-brownie-box",
				"1250.00", "ACTIVE");
		UUID size = group("BOX_SIZE", "Box size", "SINGLE", 1, 1);
		UUID six = option(size, "6 pieces", "0.00", true);
		UUID twelve = option(size, "12 pieces", "600.00", true);
		UUID retired = option(size, "24 pieces", "1500.00", false);
		option(size, "Party tray", "3000.00", true); // exists, but not allowed for this product
		UUID message = group("GIFT_NOTE", "Gift note", "TEXT", 0, 1);
		UUID toppings = group("TOPPINGS", "Toppings", "MULTIPLE", 0, 3);
		UUID nuts = option(toppings, "Walnuts", "80.00", true);
		assign(product, size, true, null, six, twelve, retired);
		assign(product, message, false, 50);
		assign(product, toppings, false, null, nuts);

		mockMvc.perform(get("/api/v1/products/fudge-brownie-box"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.customizationGroups[*].code", contains("BOX_SIZE", "GIFT_NOTE", "TOPPINGS")))
			.andExpect(jsonPath("$.customizationGroups[0].name").value("Box size"))
			.andExpect(jsonPath("$.customizationGroups[0].selectionType").value("SINGLE"))
			.andExpect(jsonPath("$.customizationGroups[0].required").value(true))
			.andExpect(jsonPath("$.customizationGroups[0].options[*].name", contains("12 pieces", "6 pieces")))
			.andExpect(jsonPath("$.customizationGroups[0].textMaxLength").isEmpty())
			.andExpect(jsonPath("$.customizationGroups[1].textMaxLength").value(50))
			.andExpect(jsonPath("$.customizationGroups[1].options", empty()))
			.andExpect(jsonPath("$.customizationGroups[2].maxSelections").value(3))
			.andExpect(jsonPath("$.customizationGroups[2].options[0].priceDelta").value(80.00));
	}

	@Test
	void textGroupsWithoutAProductLimitReportTheDatabaseLimit() throws Exception {
		UUID product = product(category("Cakes", "cakes", 1, true), "Message Cake", "message-cake", "2500.00",
				"ACTIVE");
		assign(product, group("CAKE_MESSAGE", "Message", "TEXT", 0, 1), false, null);

		mockMvc.perform(get("/api/v1/products/message-cake"))
			.andExpect(jsonPath("$.customizationGroups[0].textMaxLength").value(250));
	}

	@Test
	void hiddenProductsAreIndistinguishableFromMissingOnes() throws Exception {
		UUID active = category("Brownies", "brownies", 1, true);
		UUID inactive = category("Seasonal", "seasonal", 2, false);
		product(active, "Draft", "draft-box", "1.00", "DRAFT");
		product(active, "Archived", "archived-box", "1.00", "ARCHIVED");
		product(inactive, "Hidden", "hidden-box", "1.00", "ACTIVE");

		for (String slug : new String[] { "draft-box", "archived-box", "hidden-box", "no-such-box" }) {
			mockMvc.perform(get("/api/v1/products/" + slug))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"))
				.andExpect(jsonPath("$.detail").value("Product not found."));
		}
	}

}
