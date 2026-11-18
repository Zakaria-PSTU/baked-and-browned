package com.bakedandbrowned.customization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.bakedandbrowned.catalog.CatalogIntegrationTest;

/** Contract tag {@code Admin}: customization groups, options and product assignments. */
class CustomizationAdminIntegrationTests extends CatalogIntegrationTest {

	@Test
	void customizationAdminRequiresTheAdminRole() throws Exception {
		String customer = bearer("CUSTOMER");
		mockMvc.perform(get("/api/v1/admin/customization-groups").header(HttpHeaders.AUTHORIZATION, customer))
			.andExpect(status().isForbidden());
		send(put("/api/v1/admin/products/" + UUID.randomUUID() + "/customizations"), customer, "[]")
			.andExpect(status().isForbidden());
	}

	// ------------------------------------------------------------------ groups and options

	@Test
	void groupsAreValidatedAndAudited() throws Exception {
		String admin = bearer("ADMIN");
		send(post("/api/v1/admin/customization-groups"), admin, groupJson("BOX_SIZE", "SINGLE", 1, 1))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.code").value("BOX_SIZE"))
			.andExpect(jsonPath("$.options", empty()));

		send(post("/api/v1/admin/customization-groups"), admin, groupJson("BOX_SIZE", "MULTIPLE", 0, 3))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("GROUP_CODE_TAKEN"));
		for (String bad : List.of(groupJson("TOPPINGS", "SINGLE", 0, 2), groupJson("NOTE", "TEXT", 0, 2),
				groupJson("TOPPINGS", "MULTIPLE", 3, 2))) {
			send(post("/api/v1/admin/customization-groups"), admin, bad)
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("INVALID_GROUP"));
		}
		for (String code : List.of("box_size", "B", "BOX-SIZE")) {
			send(post("/api/v1/admin/customization-groups"), admin, groupJson(code, "MULTIPLE", 0, 3))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		}
		assertThat(jdbc.queryForList("SELECT action FROM audit_events", String.class))
			.containsExactly("CUSTOMIZATION_GROUP_CREATED");
	}

	@Test
	void optionsBelongToNonTextGroupsAndHaveUniqueNames() throws Exception {
		String admin = bearer("ADMIN");
		UUID toppings = group("TOPPINGS", "Toppings", "MULTIPLE", 0, 3);
		UUID note = group("GIFT_NOTE", "Gift note", "TEXT", 0, 1);

		send(post("/api/v1/admin/customization-groups/" + toppings + "/options"), admin,
				"{\"name\":\"Walnuts\",\"priceDelta\":80}")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.groupId").value(toppings.toString()))
			.andExpect(jsonPath("$.active").value(true));
		send(post("/api/v1/admin/customization-groups/" + toppings + "/options"), admin,
				"{\"name\":\" Walnuts \",\"priceDelta\":90}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("OPTION_NAME_TAKEN"));
		send(post("/api/v1/admin/customization-groups/" + note + "/options"), admin,
				"{\"name\":\"Anything\",\"priceDelta\":0}")
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("TEXT_GROUP_HAS_NO_OPTIONS"));
		for (String price : List.of("-1", "1.005")) {
			send(post("/api/v1/admin/customization-groups/" + toppings + "/options"), admin,
					"{\"name\":\"Bad\",\"priceDelta\":" + price + "}")
				.andExpect(status().isUnprocessableContent());
		}
		send(post("/api/v1/admin/customization-groups/" + UUID.randomUUID() + "/options"), admin,
				"{\"name\":\"X\",\"priceDelta\":0}")
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("GROUP_NOT_FOUND"));
	}

	@Test
	void selectionTypeIsLockedOnceOptionsExist() throws Exception {
		String admin = bearer("ADMIN");
		UUID empty = group("EMPTY", "Empty", "SINGLE", 0, 1);
		UUID toppings = group("TOPPINGS", "Toppings", "MULTIPLE", 0, 3);
		option(toppings, "Walnuts", "80.00", true);

		send(patch("/api/v1/admin/customization-groups/" + empty), admin, groupJson("EMPTY", "MULTIPLE", 0, 4))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.selectionType").value("MULTIPLE"));
		send(patch("/api/v1/admin/customization-groups/" + toppings), admin, groupJson("TOPPINGS", "SINGLE", 0, 1))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SELECTION_TYPE_LOCKED"));
		// Other fields can still change.
		send(patch("/api/v1/admin/customization-groups/" + toppings), admin, groupJson("TOPPINGS", "MULTIPLE", 1, 2))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.minSelections").value(1))
			.andExpect(jsonPath("$.options[0].name").value("Walnuts"));
	}

	@Test
	void adminListShowsInactiveOptionsAndOptionUpdatesAreAudited() throws Exception {
		String admin = bearer("ADMIN");
		UUID toppings = group("TOPPINGS", "Toppings", "MULTIPLE", 0, 3);
		UUID walnut = option(toppings, "Walnuts", "80.00", true);
		group("BOX_SIZE", "Box size", "SINGLE", 1, 1);

		send(patch("/api/v1/admin/customization-options/" + walnut), admin,
				"{\"name\":\"Walnuts\",\"priceDelta\":95,\"active\":false}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.active").value(false));
		mockMvc.perform(get("/api/v1/admin/customization-groups").header(HttpHeaders.AUTHORIZATION, admin))
			.andExpect(jsonPath("$[*].code", contains("BOX_SIZE", "TOPPINGS")))
			.andExpect(jsonPath("$[1].options[0].active").value(false))
			.andExpect(jsonPath("$[1].options[0].priceDelta").value(95.00));
		String details = jdbc.queryForObject(
				"SELECT details::text FROM audit_events WHERE action = 'CUSTOMIZATION_OPTION_UPDATED'", String.class);
		assertThat(details).contains("\"priceDelta\": \"80.00\"").contains("\"priceDelta\": \"95.00\"");
		send(patch("/api/v1/admin/customization-options/" + UUID.randomUUID()), admin,
				"{\"name\":\"X\",\"priceDelta\":0}")
			.andExpect(status().isNotFound());
	}

	// ------------------------------------------------------------------ product assignments

	@Test
	void assignmentsReplaceTheProductsCustomizations() throws Exception {
		String admin = bearer("ADMIN");
		UUID product = product(category("Brownies", "brownies", 1, true), "Fudge Box", "fudge-box", "1250.00",
				"ACTIVE");
		UUID size = group("BOX_SIZE", "Box size", "SINGLE", 1, 1);
		UUID six = option(size, "6 pieces", "0.00", true);
		UUID twelve = option(size, "12 pieces", "600.00", true);
		UUID note = group("GIFT_NOTE", "Gift note", "TEXT", 0, 1);
		UUID toppings = group("TOPPINGS", "Toppings", "MULTIPLE", 0, 3);
		UUID walnut = option(toppings, "Walnuts", "80.00", true);
		assign(product, toppings, false, null, walnut);

		send(put("/api/v1/admin/products/" + product + "/customizations"), admin, """
				[{"groupId":"%s","required":true,"allowedOptionIds":["%s","%s"]},
				 {"groupId":"%s","required":false,"allowedOptionIds":[],"textMaxLength":40}]"""
			.formatted(size, six, twelve, note))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.slug").value("fudge-box"))
			.andExpect(jsonPath("$.customizationGroups[*].code", contains("BOX_SIZE", "GIFT_NOTE")))
			.andExpect(jsonPath("$.customizationGroups[0].options[*].name", contains("12 pieces", "6 pieces")))
			.andExpect(jsonPath("$.customizationGroups[1].textMaxLength").value(40));

		// The previous TOPPINGS assignment is gone, publicly too.
		mockMvc.perform(get("/api/v1/products/fudge-box"))
			.andExpect(jsonPath("$.customizationGroups[*].code", contains("BOX_SIZE", "GIFT_NOTE")));
		String details = jdbc.queryForObject(
				"SELECT details::text FROM audit_events WHERE action = 'PRODUCT_CUSTOMIZATIONS_SET'", String.class);
		assertThat(details).contains("\"groupCode\": \"BOX_SIZE\"").contains("\"allowedOptions\": 2");

		send(put("/api/v1/admin/products/" + product + "/customizations"), admin, "[]")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.customizationGroups", empty()));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM product_customization_options", Integer.class)).isZero();
	}

	@Test
	void invalidAssignmentsChangeNothing() throws Exception {
		String admin = bearer("ADMIN");
		UUID product = product(category("Brownies", "brownies", 1, true), "Fudge Box", "fudge-box", "1250.00",
				"ACTIVE");
		UUID size = group("BOX_SIZE", "Box size", "SINGLE", 1, 1);
		UUID six = option(size, "6 pieces", "0.00", true);
		UUID toppings = group("TOPPINGS", "Toppings", "MULTIPLE", 2, 3);
		UUID walnut = option(toppings, "Walnuts", "80.00", true);
		UUID note = group("GIFT_NOTE", "Gift note", "TEXT", 0, 1);
		assign(product, size, true, null, six);
		String uri = "/api/v1/admin/products/" + product + "/customizations";

		String[][] cases = {
				{ "[{\"groupId\":\"%s\",\"required\":false,\"allowedOptionIds\":[]},{\"groupId\":\"%s\",\"required\":true,\"allowedOptionIds\":[]}]"
					.formatted(note, note), "DUPLICATE_GROUP" },
				{ "[{\"groupId\":\"%s\",\"required\":false,\"allowedOptionIds\":[]}]".formatted(UUID.randomUUID()),
						"UNKNOWN_GROUP" },
				{ "[{\"groupId\":\"%s\",\"required\":false,\"allowedOptionIds\":[\"%s\"]}]".formatted(size, walnut),
						"OPTION_NOT_IN_GROUP" },
				{ "[{\"groupId\":\"%s\",\"required\":false,\"allowedOptionIds\":[\"%s\"]}]".formatted(note, six),
						"OPTION_NOT_IN_GROUP" },
				{ "[{\"groupId\":\"%s\",\"required\":false,\"allowedOptionIds\":[\"%s\",\"%s\"]}]".formatted(size, six,
						six), "INVALID_ASSIGNMENT" },
				{ "[{\"groupId\":\"%s\",\"required\":false,\"allowedOptionIds\":[\"%s\"],\"textMaxLength\":10}]"
					.formatted(size, six), "INVALID_ASSIGNMENT" },
				// TOPPINGS needs at least 2 selections, so a required assignment needs at least 2 options.
				{ "[{\"groupId\":\"%s\",\"required\":true,\"allowedOptionIds\":[\"%s\"]}]".formatted(toppings, walnut),
						"INVALID_ASSIGNMENT" },
				{ "[{\"groupId\":\"%s\",\"required\":false,\"allowedOptionIds\":[],\"textMaxLength\":251}]"
					.formatted(note), "VALIDATION_FAILED" },
				{ "[{\"groupId\":\"%s\",\"allowedOptionIds\":[]}]".formatted(note), "VALIDATION_FAILED" } };
		for (String[] c : cases) {
			send(put(uri), admin, c[0]).andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value(c[1]));
		}
		send(put("/api/v1/admin/products/" + UUID.randomUUID() + "/customizations"), admin, "[]")
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));

		// The original assignment survived every rejected request.
		assertThat(jdbc.queryForList("SELECT group_id FROM product_customization_groups", UUID.class))
			.containsExactly(size);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events", Integer.class)).isZero();
	}

	// ------------------------------------------------------------------ helpers

	private ResultActions send(MockHttpServletRequestBuilder request, String bearer, String json) throws Exception {
		return mockMvc.perform(
				request.header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private static String groupJson(String code, String type, int min, int max) {
		return """
				{"code":"%s","name":"Group %s","selectionType":"%s","minSelections":%d,"maxSelections":%d}"""
			.formatted(code, code, type, min, max);
	}

}
