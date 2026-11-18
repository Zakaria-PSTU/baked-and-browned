package com.bakedandbrowned.ordering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.bakedandbrowned.customization.service.ProductCustomizationService;
import com.bakedandbrowned.customization.service.SelectionValidator;

/**
 * ADR-0020: per-product pairs of options that cannot be chosen together. ADMINs replace the list,
 * customers see it on the product, quote and checkout reject a cart choosing both, and replacing the
 * product's customizations drops pairs whose options it no longer allows.
 */
class DisallowedCombinationIntegrationTests extends CheckoutIntegrationTest {

	private String admin;

	private String customer;

	private UUID toppings;

	private UUID walnut;

	private UUID caramel;

	@BeforeEach
	void cake() {
		admin = bearer("ADMIN");
		customer = bearer("CUSTOMER");
		toppings = group("TOPPINGS", "Toppings", "MULTIPLE", 0, 2);
		walnut = option(toppings, "Walnuts", "80.00", true);
		caramel = option(toppings, "Caramel drizzle", "120.00", true);
		assign(fudge, toppings, false, null, walnut, caramel);
	}

	@Test
	void aCartChoosingBothOptionsOfAPairIsRejected() throws Exception {
		set(pair(twelve, caramel), pair(walnut, caramel)).andExpect(status().isOk());

		quote(line(fudge, 1, twelve, caramel)).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value(SelectionValidator.DISALLOWED_COMBINATION));
		place(customer, order(UUID.randomUUID(), "COD", slot, line(fudge, 1, walnut, six, caramel)))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value(SelectionValidator.DISALLOWED_COMBINATION))
			.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.allOf(
					org.hamcrest.Matchers.containsString("\"Walnuts\""),
					org.hamcrest.Matchers.containsString("\"Caramel drizzle\""))));
		assertThat(count("orders")).isZero();
		assertThat(reservedUnits(slot)).isZero();

		// Each option is fine on its own, or with anything outside its pairs.
		placeOrder(customer, "COD", slot, line(fudge, 1, twelve, walnut));
		quote(line(fudge, 1, six, caramel)).andExpect(status().isOk());
	}

	@Test
	void adminsReplaceTheListAndCustomersSeeIt() throws Exception {
		String saved = set(pair(caramel, twelve)).andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andReturn()
			.getResponse()
			.getContentAsString();
		// Stored once, in a fixed order, whichever order it was sent in.
		List<UUID> stored = orderedIds(saved);
		assertThat(stored).containsExactlyInAnyOrder(caramel, twelve);
		mockMvc.perform(get("/api/v1/admin/products/{id}/disallowed-combinations", fudge)
				.header(HttpHeaders.AUTHORIZATION, admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].optionIds[0]").value(stored.get(0).toString()));
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM audit_events WHERE entity_id = ? AND action = 'PRODUCT_DISALLOWED_COMBINATIONS_SET'",
				Integer.class, fudge)).isEqualTo(1);

		mockMvc.perform(get("/api/v1/products/fudge-box"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.disallowedCombinations.length()").value(1))
			.andExpect(jsonPath("$.disallowedCombinations[0].optionIds.length()").value(2));

		// An inactive option's pairs are kept but not shown to customers.
		jdbc.update("UPDATE customization_options SET active = FALSE WHERE id = ?", caramel);
		mockMvc.perform(get("/api/v1/products/fudge-box"))
			.andExpect(jsonPath("$.disallowedCombinations.length()").value(0));
		mockMvc.perform(get("/api/v1/admin/products/{id}/disallowed-combinations", fudge)
				.header(HttpHeaders.AUTHORIZATION, admin))
			.andExpect(jsonPath("$.length()").value(1));

		set().andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
		assertThat(count("product_disallowed_option_pairs")).isZero();
	}

	@Test
	void invalidListsAreRejectedAndChangeNothing() throws Exception {
		set(pair(walnut, caramel)).andExpect(status().isOk());
		UUID notOffered = option(toppings, "Sprinkles", "40.00", true);

		for (String body : List.of(pair(walnut, walnut), pair(walnut, notOffered), pair(walnut, UUID.randomUUID()))) {
			set(body).andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value(ProductCustomizationService.INVALID_COMBINATION));
		}
		set(pair(walnut, caramel), pair(caramel, walnut)).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.detail").value("The same combination is listed more than once."));
		send(put("/api/v1/admin/products/{id}/disallowed-combinations", fudge), admin,
				"[{\"optionIds\":[\"%s\"]}]".formatted(walnut))
			.andExpect(status().isUnprocessableContent());
		send(put("/api/v1/admin/products/{id}/disallowed-combinations", UUID.randomUUID()), admin, "[]")
			.andExpect(status().isNotFound());
		send(put("/api/v1/admin/products/{id}/disallowed-combinations", fudge), customer, "[]")
			.andExpect(status().isForbidden());
		mockMvc.perform(get("/api/v1/admin/products/{id}/disallowed-combinations", fudge)
				.header(HttpHeaders.AUTHORIZATION, customer))
			.andExpect(status().isForbidden());

		assertThat(count("product_disallowed_option_pairs")).isEqualTo(1);
	}

	@Test
	void replacingCustomizationsDropsOnlyPairsWhoseOptionsAreGone() throws Exception {
		set(pair(twelve, caramel), pair(walnut, caramel), pair(six, walnut)).andExpect(status().isOk());
		UUID size = jdbc.queryForObject("SELECT group_id FROM customization_options WHERE id = ?", UUID.class, six);

		// Caramel is no longer offered; everything else stays.
		send(put("/api/v1/admin/products/{id}/customizations", fudge), admin, """
				[{"groupId":"%s","required":true,"allowedOptionIds":["%s","%s"]},
				 {"groupId":"%s","required":false,"allowedOptionIds":["%s"]}]"""
			.formatted(size, six, twelve, toppings, walnut))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.disallowedCombinations.length()").value(1));

		assertThat(jdbc.queryForList("SELECT option_a FROM product_disallowed_option_pairs WHERE product_id = ?",
				UUID.class, fudge)).hasSize(1);
		assertThat(jdbc.queryForObject("""
				SELECT details->>'disallowedCombinationsDropped' FROM audit_events
				WHERE entity_id = ? AND action = 'PRODUCT_CUSTOMIZATIONS_SET'""", String.class, fudge)).isEqualTo("2");
		place(customer, order(UUID.randomUUID(), "COD", slot, line(fudge, 1, six, walnut)))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value(SelectionValidator.DISALLOWED_COMBINATION));
	}

	// ------------------------------------------------------------------ helpers

	private static String pair(UUID first, UUID second) {
		return "{\"optionIds\":[\"%s\",\"%s\"]}".formatted(first, second);
	}

	private ResultActions set(String... pairs) throws Exception {
		return send(put("/api/v1/admin/products/{id}/disallowed-combinations", fudge), admin,
				"[" + String.join(",", pairs) + "]");
	}

	private List<UUID> orderedIds(String body) {
		List<UUID> ids = new java.util.ArrayList<>();
		json.readTree(body).get(0).get("optionIds").forEach(n -> ids.add(UUID.fromString(n.asString())));
		assertThat(ids.get(0).toString()).isLessThan(ids.get(1).toString());
		return ids;
	}

	private ResultActions quote(String... lines) throws Exception {
		return mockMvc.perform(post("/api/v1/checkout/quote").header(HttpHeaders.AUTHORIZATION, customer)
			.contentType(MediaType.APPLICATION_JSON)
			.content(order(UUID.randomUUID(), "COD", slot, lines)));
	}

	private ResultActions send(MockHttpServletRequestBuilder request, String bearer, String body) throws Exception {
		return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

}
