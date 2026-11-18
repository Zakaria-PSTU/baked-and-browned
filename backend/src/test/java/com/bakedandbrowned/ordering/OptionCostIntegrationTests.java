package com.bakedandbrowned.ordering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.bakedandbrowned.inventory.service.RecipeService;
import com.bakedandbrowned.inventory.service.StockReservations;
import com.bakedandbrowned.ordering.service.CartPricer;

/**
 * ADR-0019: what a customization option costs the kitchen. Extra production units, notice and
 * ingredients add to the product's at quote and checkout, the order item snapshots the units, and
 * ADMINs manage it through the option and option-ingredient APIs. The fudge box needs 2 units and
 * 100 g of the 1000 g of chocolate per item; its slot holds 10 units and starts in 2 days.
 */
class OptionCostIntegrationTests extends CheckoutIntegrationTest {

	private String admin;

	private String customer;

	@BeforeEach
	void people() {
		admin = bearer("ADMIN");
		customer = bearer("CUSTOMER");
	}

	// ------------------------------------------------------------------ checkout

	@Test
	void chosenOptionsAddCapacityAndStockToTheOrder() throws Exception {
		jdbc.update("UPDATE customization_options SET extra_production_units = 1 WHERE id = ?", twelve);
		jdbc.update("INSERT INTO option_ingredients (option_id, ingredient_id, quantity_per_unit) VALUES (?, ?, 50)",
				twelve, chocolate);

		UUID orderId = placeOrder(customer, "COD", slot, line(fudge, 2, twelve));

		// (2 product + 1 option) units x 2 items; (100 g + 50 g) x 2 items.
		assertThat(reservedUnits(slot)).isEqualTo(6);
		assertThat(reservedStock(chocolate)).isEqualByComparingTo("300");
		assertThat(jdbc.queryForObject("SELECT production_units FROM order_items WHERE order_id = ?", Integer.class,
				orderId)).isEqualTo(3);

		// The same product without the option costs only what the product does.
		placeOrder(customer, "COD", slot, line(fudge, 1, six));
		assertThat(reservedUnits(slot)).isEqualTo(8);
		assertThat(reservedStock(chocolate)).isEqualByComparingTo("400");
	}

	@Test
	void anOptionsIngredientsCanRunOutOnTheirOwn() throws Exception {
		jdbc.update("INSERT INTO option_ingredients (option_id, ingredient_id, quantity_per_unit) VALUES (?, ?, 950)",
				twelve, chocolate);

		quote(line(fudge, 1, twelve)).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("OUT_OF_STOCK"));
		place(customer, order(UUID.randomUUID(), "COD", slot, line(fudge, 1, twelve))).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(StockReservations.INSUFFICIENT_STOCK));
		assertThat(reservedStock(chocolate)).isEqualByComparingTo("0");
		assertThat(reservedUnits(slot)).isZero();

		quote(line(fudge, 1, six)).andExpect(status().isOk());
	}

	@Test
	void extraUnitsCanFillTheSlot() throws Exception {
		jdbc.update("UPDATE customization_options SET extra_production_units = 4 WHERE id = ?", twelve);

		// 2 items x (2 + 4) = 12 units: more than the slot's 10.
		quote(line(fudge, 2, twelve)).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("SLOT_FULL"));
		quote(line(fudge, 2, six)).andExpect(status().isOk());
	}

	@Test
	void anOptionNeedingMoreNoticeThanTheSlotGivesIsRejectedByName() throws Exception {
		// The slot starts in 48 hours; the product needs 24.
		jdbc.update("UPDATE customization_options SET min_lead_hours = 72 WHERE id = ?", twelve);
		jdbc.update("UPDATE customization_options SET min_lead_hours = 12 WHERE id = ?", six);

		place(customer, order(UUID.randomUUID(), "COD", slot, line(fudge, 1, twelve)))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value(CartPricer.LEAD_TIME_NOT_MET))
			.andExpect(jsonPath("$.detail").value("\"12 pieces\" needs at least 72 hours' notice. Please choose a later slot."));
		// An option asking for less notice than the product never relaxes the product's.
		quote(line(fudge, 1, six)).andExpect(status().isOk());
		jdbc.update("UPDATE products SET min_lead_hours = 60 WHERE id = ?", fudge);
		quote(line(fudge, 1, six)).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("\"Fudge Brownie Box\" needs at least 60")));
	}

	@Test
	void customersSeeAnOptionsNoticeButNotItsKitchenCosts() throws Exception {
		jdbc.update("UPDATE customization_options SET min_lead_hours = 72, extra_production_units = 3 WHERE id = ?",
				twelve);

		mockMvc.perform(get("/api/v1/products/fudge-box"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.customizationGroups[?(@.code == 'BOX_SIZE')].options[?(@.name == '12 pieces')].minLeadHours")
				.value(72))
			.andExpect(jsonPath("$.customizationGroups[?(@.code == 'BOX_SIZE')].options[?(@.name == '6 pieces')].minLeadHours")
				.value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.nullValue())))
			.andExpect(jsonPath("$..extraProductionUnits").isEmpty());
	}

	// ------------------------------------------------------------------ admin

	@Test
	void adminsSetAnOptionsKitchenCostsAndAWriteReplacesThem() throws Exception {
		UUID group = jdbc.queryForObject("SELECT group_id FROM customization_options WHERE id = ?", UUID.class, twelve);

		String created = send(post("/api/v1/admin/customization-groups/{id}/options", group), admin, """
				{"name":"18 pieces","priceDelta":1100.00,"extraProductionUnits":2,"minLeadHours":48}""")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.extraProductionUnits").value(2))
			.andExpect(jsonPath("$.minLeadHours").value(48))
			.andReturn()
			.getResponse()
			.getContentAsString();
		UUID option = UUID.fromString(json.readTree(created).get("id").asString());

		// Full replacement: fields left out go back to none.
		send(patch("/api/v1/admin/customization-options/{id}", option), admin, """
				{"name":"18 pieces","priceDelta":1100.00}""")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.extraProductionUnits").value(0))
			.andExpect(jsonPath("$.minLeadHours").doesNotExist());
		assertThat(jdbc.queryForList("""
				SELECT details->'to'->>'extraProductionUnits' FROM audit_events
				WHERE entity_id = ? AND action = 'CUSTOMIZATION_OPTION_UPDATED'""", String.class, option))
			.containsExactly("0");

		for (String bad : new String[] { """
				{"name":"x","priceDelta":1.00,"extraProductionUnits":101}""", """
				{"name":"x","priceDelta":1.00,"extraProductionUnits":-1}""", """
				{"name":"x","priceDelta":1.00,"minLeadHours":721}""" }) {
			send(patch("/api/v1/admin/customization-options/{id}", option), admin, bad)
				.andExpect(status().isUnprocessableContent());
		}
	}

	@Test
	void adminsManageAnOptionsIngredients() throws Exception {
		UUID butter = ingredient("Butter", "g", "500");

		send(put("/api/v1/admin/customization-options/{id}/ingredients", twelve), admin, """
				[{"ingredientId":"%s","quantityPerUnit":40},{"ingredientId":"%s","quantityPerUnit":12.5}]"""
			.formatted(chocolate, butter))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].ingredientName").value("Butter"))
			.andExpect(jsonPath("$[0].quantityPerUnit").value(12.5));
		mockMvc.perform(get("/api/v1/admin/customization-options/{id}/ingredients", twelve)
				.header(HttpHeaders.AUTHORIZATION, admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[1].ingredientName").value("Chocolate"));
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM audit_events WHERE entity_id = ? AND action = 'OPTION_RECIPE_SET'", Integer.class,
				twelve)).isEqualTo(1);

		String body = "[{\"ingredientId\":\"%s\",\"quantityPerUnit\":1}]";
		send(put("/api/v1/admin/customization-options/{id}/ingredients", twelve), admin, body.formatted(UUID.randomUUID()))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value(RecipeService.UNKNOWN_INGREDIENT));
		send(put("/api/v1/admin/customization-options/{id}/ingredients", twelve), admin, """
				[{"ingredientId":"%s","quantityPerUnit":1},{"ingredientId":"%s","quantityPerUnit":2}]"""
			.formatted(butter, butter))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value(RecipeService.INVALID_RECIPE));
		send(put("/api/v1/admin/customization-options/{id}/ingredients", UUID.randomUUID()), admin, "[]")
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value(RecipeService.OPTION_NOT_FOUND));
		send(put("/api/v1/admin/customization-options/{id}/ingredients", twelve), customer, "[]")
			.andExpect(status().isForbidden());
		// Failed writes changed nothing; an empty list removes the lines.
		assertThat(jdbc.queryForObject("SELECT count(*) FROM option_ingredients WHERE option_id = ?", Integer.class,
				twelve)).isEqualTo(2);
		send(put("/api/v1/admin/customization-options/{id}/ingredients", twelve), admin, "[]").andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM option_ingredients WHERE option_id = ?", Integer.class,
				twelve)).isZero();
	}

	// ------------------------------------------------------------------ helpers

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
