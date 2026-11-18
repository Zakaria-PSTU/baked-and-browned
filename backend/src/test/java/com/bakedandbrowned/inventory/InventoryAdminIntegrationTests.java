package com.bakedandbrowned.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Contract tag {@code Admin}: inventory, movements and recipes. */
class InventoryAdminIntegrationTests extends InventoryIntegrationTest {

	@Test
	void inventoryRequiresTheAdminRole() throws Exception {
		mockMvc.perform(get("/api/v1/admin/inventory").header(HttpHeaders.AUTHORIZATION, bearer("CUSTOMER")))
			.andExpect(status().isForbidden());
		mockMvc.perform(get("/api/v1/admin/inventory")).andExpect(status().isUnauthorized());
	}

	@Test
	void creatingStockRecordsAnInitialMovement() throws Exception {
		String admin = bearer("ADMIN");
		String body = send(post("/api/v1/admin/inventory"), admin,
				"{\"name\":\"Dark chocolate\",\"unit\":\"g\",\"onHand\":2500.5,\"lowStockThreshold\":500}")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.reserved").value(0))
			.andExpect(jsonPath("$.lowStockThreshold").value(500))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(body).contains("\"onHand\":2500.500").contains("\"available\":2500.500");
		send(post("/api/v1/admin/inventory"), admin, "{\"name\":\"Flour\",\"unit\":\"kg\"}")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.onHand").value(0));

		assertThat(jdbc.queryForList(
				"SELECT movement_type::text || ':' || quantity_delta || ':' || on_hand_after || ':' || actor_type FROM inventory_movements",
				String.class))
			.containsExactly("INITIAL:2500.500:2500.500:ADMIN");
		assertThat(jdbc.queryForList("SELECT action FROM audit_events ORDER BY created_at", String.class))
			.containsExactly("INGREDIENT_CREATED", "INGREDIENT_CREATED");
		mockMvc.perform(get("/api/v1/admin/inventory").header(HttpHeaders.AUTHORIZATION, admin))
			.andExpect(jsonPath("$[*].name", contains("Dark chocolate", "Flour")));
	}

	@Test
	void ingredientsAreValidated() throws Exception {
		String admin = bearer("ADMIN");
		ingredient("Butter", "g", "100");
		send(post("/api/v1/admin/inventory"), admin, "{\"name\":\"Butter\",\"unit\":\"g\"}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("INGREDIENT_EXISTS"));
		for (String bad : List.of("{\"name\":\"B\",\"unit\":\"g\"}", "{\"name\":\"Sugar\",\"unit\":\"\"}",
				"{\"name\":\"Sugar\",\"unit\":\"g\",\"onHand\":-1}", "{\"name\":\"Sugar\",\"unit\":\"g\",\"onHand\":1.0005}")) {
			send(post("/api/v1/admin/inventory"), admin, bad).andExpect(status().isUnprocessableContent());
		}
	}

	@Test
	void adjustmentsAreLedgeredAndNeverBelowReserved() throws Exception {
		String admin = bearer("ADMIN");
		UUID cocoa = ingredient("Cocoa", "g", "1000");
		jdbc.update("UPDATE ingredients SET reserved = 300 WHERE id = ?", cocoa);
		String uri = "/api/v1/admin/inventory/" + cocoa;

		send(patch(uri), admin, "{\"onHand\":299.999,\"reason\":\"Stock count\"}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("BELOW_RESERVED"));
		send(patch(uri), admin, "{\"onHand\":800,\"reason\":\"Spilled a bag\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.onHand").value(800))
			.andExpect(jsonPath("$.reserved").value(300))
			.andExpect(jsonPath("$.available").value(500));
		send(patch(uri), admin, "{\"onHand\":800,\"reason\":\"Recount, unchanged\"}").andExpect(status().isOk());
		send(patch(uri), admin, "{\"onHand\":1800,\"reason\":\"Delivery from supplier\"}").andExpect(status().isOk());
		send(patch(uri), admin, "{\"onHand\":900,\"reason\":\"x\"}").andExpect(status().isUnprocessableContent());
		send(patch(uri), admin, "{\"onHand\":900}").andExpect(status().isUnprocessableContent());
		send(patch("/api/v1/admin/inventory/" + UUID.randomUUID()), admin, "{\"onHand\":1,\"reason\":\"Count\"}")
			.andExpect(status().isNotFound());

		List<Map<String, Object>> ledger = jdbc.queryForList("""
				SELECT quantity_delta, on_hand_after, reason, actor_user_id FROM inventory_movements
				ORDER BY created_at, on_hand_after""");
		assertThat(ledger).extracting(m -> m.get("quantity_delta"))
			.containsExactly(new BigDecimal("-200.000"), new BigDecimal("1000.000"));
		assertThat(ledger).extracting(m -> m.get("reason")).containsExactly("Spilled a bag", "Delivery from supplier");
		assertThat(ledger).allSatisfy(m -> assertThat(m.get("actor_user_id")).isEqualTo(userIdOf(admin)));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'INVENTORY_ADJUSTED'",
				Integer.class)).isEqualTo(2);
	}

	@Test
	void movementsArePagedNewestFirst() throws Exception {
		String admin = bearer("ADMIN");
		UUID sugar = ingredient("Sugar", "g", "100");
		for (int onHand : new int[] { 200, 300, 400 }) {
			send(patch("/api/v1/admin/inventory/" + sugar), admin, "{\"onHand\":" + onHand + ",\"reason\":\"Restock\"}");
		}

		mockMvc.perform(get("/api/v1/admin/inventory/" + sugar + "/movements").param("size", "2")
			.header(HttpHeaders.AUTHORIZATION, admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(3))
			.andExpect(jsonPath("$.content[*].onHandAfter", contains(400.0, 300.0)))
			.andExpect(jsonPath("$.content[0].movementType").value("ADJUSTMENT"))
			.andExpect(jsonPath("$.content[0].actorType").value("ADMIN"))
			.andExpect(jsonPath("$.content[0].quantityDelta").value(100.0));
		mockMvc.perform(get("/api/v1/admin/inventory/" + UUID.randomUUID() + "/movements")
			.header(HttpHeaders.AUTHORIZATION, admin)).andExpect(status().isNotFound());
	}

	// ------------------------------------------------------------------ recipes

	@Test
	void recipesAreReplacedAndValidated() throws Exception {
		String admin = bearer("ADMIN");
		UUID product = product(category("Brownies", "brownies", 1, true), "Fudge Box", "fudge-box", "1250.00", "ACTIVE");
		UUID butter = ingredient("Butter", "g", "1000");
		UUID cocoa = ingredient("Cocoa", "g", "1000");
		String uri = "/api/v1/admin/products/" + product + "/ingredients";

		send(put(uri), admin, "[{\"ingredientId\":\"%s\",\"quantityPerUnit\":45.5},{\"ingredientId\":\"%s\",\"quantityPerUnit\":60}]"
			.formatted(cocoa, butter))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[*].ingredientName", contains("Butter", "Cocoa")))
			.andExpect(jsonPath("$[0].unit").value("g"))
			.andExpect(jsonPath("$[1].quantityPerUnit").value(45.5));
		mockMvc.perform(get(uri).header(HttpHeaders.AUTHORIZATION, admin))
			.andExpect(jsonPath("$[*].ingredientName", contains("Butter", "Cocoa")));

		for (String[] bad : new String[][] {
				{ "[{\"ingredientId\":\"%s\",\"quantityPerUnit\":1},{\"ingredientId\":\"%s\",\"quantityPerUnit\":2}]"
					.formatted(butter, butter), "INVALID_RECIPE" },
				{ "[{\"ingredientId\":\"%s\",\"quantityPerUnit\":0}]".formatted(butter), "INVALID_RECIPE" },
				{ "[{\"ingredientId\":\"%s\",\"quantityPerUnit\":1}]".formatted(UUID.randomUUID()), "UNKNOWN_INGREDIENT" },
				{ "[{\"ingredientId\":\"%s\",\"quantityPerUnit\":1.0001}]".formatted(butter), "VALIDATION_FAILED" } }) {
			send(put(uri), admin, bad[0]).andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value(bad[1]));
		}
		// The rejected requests left the recipe alone.
		assertThat(jdbc.queryForObject("SELECT count(*) FROM product_ingredients", Integer.class)).isEqualTo(2);

		send(put(uri), admin, "[]").andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM product_ingredients", Integer.class)).isZero();
		mockMvc.perform(get("/api/v1/admin/products/" + UUID.randomUUID() + "/ingredients")
			.header(HttpHeaders.AUTHORIZATION, admin)).andExpect(status().isNotFound());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'PRODUCT_RECIPE_SET'",
				Integer.class)).isEqualTo(2);
	}

	private ResultActions send(MockHttpServletRequestBuilder request, String bearer, String json) throws Exception {
		return mockMvc.perform(
				request.header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON).content(json));
	}

}
