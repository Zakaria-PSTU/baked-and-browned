package com.bakedandbrowned.ordering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.bakedandbrowned.customization.service.SelectionValidator;
import com.bakedandbrowned.ordering.service.CartPricer;

/**
 * Phase 4 acceptance: a custom cake set up entirely through the admin API (ADR-0019, ADR-0020,
 * ADR-0021) and ordered by a customer. The cake costs 2200.00, takes 3 kitchen units and 24 hours'
 * notice, and uses 200 g of chocolate; customers pick a size, a flavor, optionally a frosting and up
 * to three toppings, and may add a message of up to 30 characters. The fixture slot starts in two
 * days and holds 10 units.
 */
class CakeOrderIntegrationTests extends CheckoutIntegrationTest {

	private String admin;

	private String customer;

	private UUID cake;

	private UUID size;

	private UUID message;

	private UUID oneKg;

	private UUID twoKg;

	private UUID halfKg;

	private UUID chocolateFlavor;

	private UUID redVelvet;

	private UUID fondant;

	private UUID strawberries;

	private UUID walnuts;

	private UUID sprinkles;

	private UUID goldLeaf;

	private UUID sugar;

	@BeforeEach
	void cake() throws Exception {
		admin = bearer("ADMIN");
		customer = bearer("CUSTOMER");
		sugar = ingredient("Sugar", "g", "1000");
		UUID cakes = category("Cakes", "cakes", 3, true);
		cake = id(send(post("/api/v1/admin/products"), admin, """
				{"categoryId":"%s","name":"Celebration Cake","slug":"celebration-cake","basePrice":2200.00,
				 "minLeadHours":24,"productionUnits":3,"status":"ACTIVE"}""".formatted(cakes)));
		send(put("/api/v1/admin/products/{id}/ingredients", cake), admin,
				"[{\"ingredientId\":\"%s\",\"quantityPerUnit\":200}]".formatted(chocolate))
			.andExpect(status().isOk());

		size = adminGroup("CAKE_SIZE", "Size", "SINGLE", 1, 1);
		halfKg = adminOption(size, "500 g", "0.00", null);
		oneKg = adminOption(size, "1 kg", "0.00", null);
		twoKg = adminOption(size, "2 kg", "1800.00", "\"extraProductionUnits\":3,\"minLeadHours\":36");
		UUID flavor = adminGroup("CAKE_FLAVOR", "Flavor", "SINGLE", 1, 1);
		chocolateFlavor = adminOption(flavor, "Chocolate", "0.00", null);
		redVelvet = adminOption(flavor, "Red velvet", "200.00", null);
		UUID frosting = adminGroup("CAKE_FROSTING", "Frosting", "SINGLE", 1, 1);
		UUID buttercream = adminOption(frosting, "Buttercream", "0.00", null);
		fondant = adminOption(frosting, "Fondant", "500.00", "\"minLeadHours\":72");
		UUID toppings = adminGroup("CAKE_TOPPINGS", "Toppings", "MULTIPLE", 0, 3);
		strawberries = adminOption(toppings, "Strawberries", "150.00", null);
		walnuts = adminOption(toppings, "Walnuts", "80.00", null);
		sprinkles = adminOption(toppings, "Sprinkles", "40.00", null);
		goldLeaf = adminOption(toppings, "Gold leaf", "600.00", null);
		message = adminGroup("CAKE_MESSAGE", "Message on the cake", "TEXT", 0, 1);
		send(put("/api/v1/admin/customization-options/{id}/ingredients", fondant), admin,
				"[{\"ingredientId\":\"%s\",\"quantityPerUnit\":300}]".formatted(sugar))
			.andExpect(status().isOk());

		// Listed in the order customers should see them, not by code or name.
		send(put("/api/v1/admin/products/{id}/customizations", cake), admin, """
				[{"groupId":"%s","required":true,"allowedOptionIds":["%s","%s","%s"]},
				 {"groupId":"%s","required":true,"allowedOptionIds":["%s","%s"]},
				 {"groupId":"%s","required":false,"allowedOptionIds":["%s","%s"]},
				 {"groupId":"%s","required":false,"allowedOptionIds":["%s","%s","%s","%s"]},
				 {"groupId":"%s","required":false,"allowedOptionIds":[],"textMaxLength":30}]"""
			.formatted(size, halfKg, oneKg, twoKg, flavor, chocolateFlavor, redVelvet, frosting, buttercream, fondant,
					toppings, strawberries, walnuts, sprinkles, goldLeaf, message))
			.andExpect(status().isOk());
		// Fresh fruit slides off fondant.
		send(put("/api/v1/admin/products/{id}/disallowed-combinations", cake), admin,
				"[{\"optionIds\":[\"%s\",\"%s\"]}]".formatted(fondant, strawberries))
			.andExpect(status().isOk());
	}

	@Test
	void customersSeeTheCakeInTheOrderTheAdminChose() throws Exception {
		mockMvc.perform(get("/api/v1/products/celebration-cake"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.customizationGroups[*].code",
					Matchers.contains("CAKE_SIZE", "CAKE_FLAVOR", "CAKE_FROSTING", "CAKE_TOPPINGS", "CAKE_MESSAGE")))
			.andExpect(jsonPath("$.customizationGroups[0].options[*].name", Matchers.contains("500 g", "1 kg", "2 kg")))
			.andExpect(jsonPath("$.customizationGroups[3].options[*].name",
					Matchers.contains("Strawberries", "Walnuts", "Sprinkles", "Gold leaf")))
			.andExpect(jsonPath("$.customizationGroups[4].textMaxLength").value(30))
			.andExpect(jsonPath("$.disallowedCombinations.length()").value(1));

		// Re-listing reorders; nothing else about the product changes.
		send(put("/api/v1/admin/products/{id}/customizations", cake), admin, """
				[{"groupId":"%s","required":false,"allowedOptionIds":[],"textMaxLength":30},
				 {"groupId":"%s","required":true,"allowedOptionIds":["%s","%s","%s"]}]"""
			.formatted(message, size, twoKg, oneKg, halfKg))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.customizationGroups[*].code", Matchers.contains("CAKE_MESSAGE", "CAKE_SIZE")))
			.andExpect(jsonPath("$.customizationGroups[1].options[*].name", Matchers.contains("2 kg", "1 kg", "500 g")));
	}

	@Test
	void aFullyCustomizedCakeIsPricedReservedAndSnapshotted() throws Exception {
		String text = "<b>Happy 30th</b> Rafi";
		String line = lineWithNote(cake, 1, message, text, twoKg, redVelvet, strawberries, walnuts);

		// 2200 + 1800 (2 kg) + 200 (red velvet) + 150 + 80 (toppings).
		String quoted = quote(line).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(quoted).contains("\"subtotal\":4430.00", "\"deliveryFee\":60.00", "\"total\":4490.00");

		UUID orderId = placeOrder(customer, "COD", slot, line);
		// 3 cake units + 3 for the 2 kg size; 200 g of chocolate; no fondant, so no sugar.
		assertThat(reservedUnits(slot)).isEqualTo(6);
		assertThat(reservedStock(chocolate)).isEqualByComparingTo("200");
		assertThat(reservedStock(sugar)).isEqualByComparingTo("0");

		// Later catalog edits never reach a placed order.
		send(patch("/api/v1/admin/customization-options/{id}", twoKg), admin, """
				{"name":"2 kg (new recipe)","priceDelta":2100.00}""")
			.andExpect(status().isOk());

		String body = mockMvc.perform(get("/api/v1/orders/{id}", orderId).header(HttpHeaders.AUTHORIZATION, customer))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].customizations[*].groupName",
					Matchers.contains("Size", "Flavor", "Toppings", "Toppings", "Message on the cake")))
			.andExpect(jsonPath("$.items[0].customizations[0].optionName").value("2 kg"))
			.andExpect(jsonPath("$.items[0].customizations[2].optionName").value("Strawberries"))
			// Stored as typed; escaping is the job of whatever displays it.
			.andExpect(jsonPath("$.items[0].customizations[4].textValue").value(text))
			.andExpect(jsonPath("$.items[0].customizations[4].optionId").isEmpty())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(body).contains("\"unitOptionsPrice\":2230.00", "\"unitPrice\":4430.00", "\"priceDelta\":1800.00");
		assertThat(jdbc.queryForObject("SELECT production_units FROM order_items WHERE order_id = ?", Integer.class,
				orderId)).isEqualTo(6);
	}

	@Test
	void fondantNeedsThreeDaysAndItsOwnIngredients() throws Exception {
		String line = line(cake, 1, oneKg, chocolateFlavor, fondant);

		quote(line).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value(CartPricer.LEAD_TIME_NOT_MET))
			.andExpect(jsonPath("$.detail").value(Matchers.containsString("\"Fondant\" needs at least 72")));

		UUID later = slot(slotStart.plus(Duration.ofDays(2)), Duration.ofHours(2), 10, 0, true);
		placeOrder(customer, "COD", later, line);
		assertThat(reservedUnits(later)).isEqualTo(3);
		assertThat(reservedStock(sugar)).isEqualByComparingTo("300");
		assertThat(reservedStock(chocolate)).isEqualByComparingTo("200");
	}

	@Test
	void invalidCakesAreRejectedAndReserveNothing() throws Exception {
		UUID later = slot(slotStart.plus(Duration.ofDays(2)), Duration.ofHours(2), 10, 0, true);
		List<Object[]> cases = List.of(
				// No flavor.
				new Object[] { line(cake, 1, oneKg), SelectionValidator.SELECTION_COUNT, "\"Flavor\"" },
				// Two sizes.
				new Object[] { line(cake, 1, oneKg, twoKg, redVelvet), SelectionValidator.SELECTION_COUNT, "\"Size\"" },
				// Four toppings.
				new Object[] { line(cake, 1, oneKg, redVelvet, strawberries, walnuts, sprinkles, goldLeaf),
						SelectionValidator.SELECTION_COUNT, "\"Toppings\"" },
				new Object[] { line(cake, 1, oneKg, redVelvet, fondant, strawberries),
						SelectionValidator.DISALLOWED_COMBINATION, "\"Strawberries\"" },
				new Object[] { lineWithNote(cake, 1, message, "x".repeat(31), oneKg, redVelvet),
						SelectionValidator.TEXT_TOO_LONG, "at most 30" },
				// A topping from another product's group.
				new Object[] { line(cake, 1, oneKg, redVelvet, twelve), SelectionValidator.OPTION_NOT_ALLOWED,
						"not available" });
		for (Object[] c : cases) {
			place(customer, order(UUID.randomUUID(), "COD", later, (String) c[0]))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value(c[1]))
				.andExpect(jsonPath("$.detail").value(Matchers.containsString((String) c[2])));
		}
		assertThat(count("orders")).isZero();
		assertThat(reservedUnits(later)).isZero();
		assertThat(reservedStock(sugar)).isEqualByComparingTo("0");

		// Exactly 30 characters, counted as code points (Bangla included), is fine.
		String bangla = "শুভ জন্মদিন".repeat(3).substring(0, 30 - 3) + "!!!";
		assertThat(bangla.codePointCount(0, bangla.length())).isEqualTo(30);
		placeOrder(customer, "COD", later, lineWithNote(cake, 1, message, bangla, oneKg, redVelvet));
	}

	// ------------------------------------------------------------------ helpers

	private UUID adminGroup(String code, String name, String type, int min, int max) throws Exception {
		return id(send(post("/api/v1/admin/customization-groups"), admin, """
				{"code":"%s","name":"%s","selectionType":"%s","minSelections":%d,"maxSelections":%d}"""
			.formatted(code, name, type, min, max)));
	}

	private UUID adminOption(UUID group, String name, String delta, String extra) throws Exception {
		return id(send(post("/api/v1/admin/customization-groups/{id}/options", group), admin,
				"{\"name\":\"%s\",\"priceDelta\":%s%s}".formatted(name, delta, extra == null ? "" : "," + extra)));
	}

	private UUID id(ResultActions created) throws Exception {
		String body = created.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return UUID.fromString(json.readTree(body).get("id").asString());
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
