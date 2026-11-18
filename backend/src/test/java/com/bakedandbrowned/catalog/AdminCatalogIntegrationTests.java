package com.bakedandbrowned.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Contract tag {@code Admin}: categories and products, with authorization and audit. */
class AdminCatalogIntegrationTests extends CatalogIntegrationTest {

	// ------------------------------------------------------------------ authorization

	@Test
	void adminCatalogRequiresTheAdminRole() throws Exception {
		String customer = bearer("CUSTOMER");
		mockMvc.perform(get("/api/v1/admin/categories").header(HttpHeaders.AUTHORIZATION, customer))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("FORBIDDEN"));
		mockMvc.perform(post("/api/v1/admin/categories").header(HttpHeaders.AUTHORIZATION, customer)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"Brownies\",\"slug\":\"brownies\"}")).andExpect(status().isForbidden());
		mockMvc.perform(get("/api/v1/admin/products")).andExpect(status().isUnauthorized());
		assertThat(count("categories")).isZero();
	}

	// ------------------------------------------------------------------ categories

	@Test
	void createsCategoriesWithDefaultsAndAuditsThem() throws Exception {
		String admin = bearer("ADMIN");
		send(post("/api/v1/admin/categories"), admin, "{\"name\":\"  Brownies \",\"slug\":\"brownies\"}")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.name").value("Brownies"))
			.andExpect(jsonPath("$.sortOrder").value(0))
			.andExpect(jsonPath("$.active").value(true));

		Map<String, Object> audit = jdbc.queryForMap(
				"SELECT actor_type::text AS actor, actor_user_id, action, entity_type, details::text AS details, trace_id FROM audit_events");
		assertThat(audit).containsEntry("actor", "ADMIN")
			.containsEntry("actor_user_id", userIdOf(admin))
			.containsEntry("action", "CATEGORY_CREATED")
			.containsEntry("entity_type", "category");
		assertThat((String) audit.get("details")).contains("\"slug\": \"brownies\"");
		assertThat((String) audit.get("trace_id")).matches("[0-9a-f]{32}");
	}

	@Test
	void categoryNamesAndSlugsAreUniqueAndValidated() throws Exception {
		String admin = bearer("ADMIN");
		category("Brownies", "brownies", 1, true);

		send(post("/api/v1/admin/categories"), admin, "{\"name\":\"Brownies\",\"slug\":\"other\"}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CATEGORY_EXISTS"));
		send(post("/api/v1/admin/categories"), admin, "{\"name\":\"Other\",\"slug\":\"brownies\"}")
			.andExpect(status().isConflict());
		for (String bad : List.of("{\"name\":\"X\",\"slug\":\"x\"}", "{\"name\":\"Cakes\",\"slug\":\"Cakes!\"}",
				"{\"name\":\"Cakes\",\"slug\":\"-cakes\"}", "{\"name\":\"Cakes\"}")) {
			send(post("/api/v1/admin/categories"), admin, bad).andExpect(status().isUnprocessableContent());
		}
		assertThat(count("categories")).isEqualTo(1);
	}

	@Test
	void updatesReplaceCategoriesAndAuditOnlyRealChanges() throws Exception {
		String admin = bearer("ADMIN");
		UUID id = category("Brownies", "brownies", 1, true);
		category("Cookies", "cookies", 2, true);

		send(patch("/api/v1/admin/categories/" + id), admin,
				"{\"name\":\"Brownies\",\"slug\":\"brownies\",\"sortOrder\":5,\"active\":false}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.sortOrder").value(5))
			.andExpect(jsonPath("$.active").value(false));
		// Same values again: nothing to audit.
		send(patch("/api/v1/admin/categories/" + id), admin,
				"{\"name\":\"Brownies\",\"slug\":\"brownies\",\"sortOrder\":5,\"active\":false}")
			.andExpect(status().isOk());
		// Taking another category's slug conflicts; keeping its own slug does not.
		send(patch("/api/v1/admin/categories/" + id), admin, "{\"name\":\"Brownies\",\"slug\":\"cookies\"}")
			.andExpect(status().isConflict());
		send(patch("/api/v1/admin/categories/" + UUID.randomUUID()), admin, "{\"name\":\"Brownies\",\"slug\":\"x-y\"}")
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"));

		List<String> details = jdbc.queryForList(
				"SELECT details::text FROM audit_events WHERE action = 'CATEGORY_UPDATED'", String.class);
		assertThat(details).singleElement()
			.satisfies(d -> assertThat(d).contains("\"sortOrder\": {\"to\": 5, \"from\": 1}")
				.contains("\"active\": {\"to\": false, \"from\": true}")
				.doesNotContain("\"name\""));
		// The admin view lists inactive categories; the public one does not.
		mockMvc.perform(get("/api/v1/admin/categories").header(HttpHeaders.AUTHORIZATION, admin))
			.andExpect(jsonPath("$[*].slug", contains("cookies", "brownies")));
		mockMvc.perform(get("/api/v1/categories")).andExpect(jsonPath("$[*].slug", contains("cookies")));
	}

	// ------------------------------------------------------------------ products

	@Test
	void createsDraftProductsInvisibleUntilActivated() throws Exception {
		String admin = bearer("ADMIN");
		UUID category = category("Brownies", "brownies", 1, true);

		String created = send(post("/api/v1/admin/products"), admin, productJson(category, "fudge-box", "1250.00", null))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("DRAFT"))
			.andExpect(jsonPath("$.cancellationCutoffHours").value(24))
			.andExpect(jsonPath("$.productionUnits").value(2))
			.andExpect(jsonPath("$.createdAt").isNotEmpty())
			.andExpect(jsonPath("$.updatedAt").isNotEmpty())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(created).contains("\"basePrice\":1250.00");
		UUID id = UUID.fromString(created.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
		mockMvc.perform(get("/api/v1/products/fudge-box")).andExpect(status().isNotFound());

		send(patch("/api/v1/admin/products/" + id), admin, productJson(category, "fudge-box", "1300.00", "ACTIVE"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ACTIVE"));
		mockMvc.perform(get("/api/v1/products/fudge-box")).andExpect(status().isOk());

		assertThat(jdbc.queryForList("SELECT action FROM audit_events ORDER BY created_at, action", String.class))
			.containsExactly("PRODUCT_CREATED", "PRODUCT_UPDATED");
		String change = jdbc.queryForObject("SELECT details::text FROM audit_events WHERE action = 'PRODUCT_UPDATED'",
				String.class);
		assertThat(change).contains("\"basePrice\": {\"to\": \"1300.00\", \"from\": \"1250.00\"}")
			.contains("\"status\": {\"to\": \"ACTIVE\", \"from\": \"DRAFT\"}");
	}

	@Test
	void updatesWithoutStatusKeepTheCurrentStatus() throws Exception {
		String admin = bearer("ADMIN");
		UUID category = category("Brownies", "brownies", 1, true);
		UUID id = product(category, "Fudge Box", "fudge-box", "1250.00", "ACTIVE");

		send(patch("/api/v1/admin/products/" + id), admin, productJson(category, "fudge-box", "1250.00", null))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ACTIVE"));
	}

	@Test
	void productWritesAreValidated() throws Exception {
		String admin = bearer("ADMIN");
		UUID category = category("Brownies", "brownies", 1, true);
		product(category, "Existing", "existing-box", "100.00", "DRAFT");

		send(post("/api/v1/admin/products"), admin, productJson(UUID.randomUUID(), "new-box", "100.00", null))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("UNKNOWN_CATEGORY"));
		send(post("/api/v1/admin/products"), admin, productJson(category, "existing-box", "100.00", null))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SLUG_TAKEN"));
		for (String price : List.of("-1.00", "10.005")) {
			send(post("/api/v1/admin/products"), admin, productJson(category, "new-box", price, null))
				.andExpect(status().isUnprocessableContent());
		}
		for (String url : List.of("http://cdn.example.com/a.jpg", "javascript:alert(1)", "//cdn.example.com/a.jpg",
				"https://user:pw@cdn.example.com/a.jpg", "data:image/png;base64,AAAA")) {
			send(post("/api/v1/admin/products"), admin,
					productJson(category, "new-box", "100.00", null).replace("}", ",\"imageUrl\":\"" + url + "\"}"))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("imageUrl")));
		}
		send(post("/api/v1/admin/products"), admin,
				productJson(category, "new-box", "100.00", null).replace("}", ",\"cancellationCutoffHours\":721}"))
			.andExpect(status().isUnprocessableContent());
		send(post("/api/v1/admin/products"), admin,
				productJson(category, "new-box", "100.00", null).replace("\"productionUnits\":2", "\"productionUnits\":0"))
			.andExpect(status().isUnprocessableContent());
		assertThat(count("products")).isEqualTo(1);

		send(post("/api/v1/admin/products"), admin, productJson(category, "new-box", "100.00", null)
			.replace("}", ",\"imageUrl\":\"https://cdn.bakedandbrowned.com.bd/fudge.jpg\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.imageUrl").value("https://cdn.bakedandbrowned.com.bd/fudge.jpg"));
	}

	@Test
	void adminListingSeesEveryStatusAndFilters() throws Exception {
		String admin = bearer("ADMIN");
		UUID brownies = category("Brownies", "brownies", 1, true);
		UUID cookies = category("Cookies", "cookies", 2, false);
		product(brownies, "A Draft", "a-draft", "1.00", "DRAFT");
		product(brownies, "B Active", "b-active", "1.00", "ACTIVE");
		product(cookies, "C Archived", "c-archived", "1.00", "ARCHIVED");

		mockMvc.perform(get("/api/v1/admin/products").header(HttpHeaders.AUTHORIZATION, admin))
			.andExpect(jsonPath("$.content[*].slug", contains("a-draft", "b-active", "c-archived")))
			.andExpect(jsonPath("$.totalElements").value(3));
		mockMvc.perform(get("/api/v1/admin/products").param("status", "ACTIVE").header(HttpHeaders.AUTHORIZATION, admin))
			.andExpect(jsonPath("$.content[*].slug", contains("b-active")));
		mockMvc.perform(get("/api/v1/admin/products").param("categoryId", cookies.toString())
			.header(HttpHeaders.AUTHORIZATION, admin)).andExpect(jsonPath("$.content[*].slug", contains("c-archived")));
		mockMvc.perform(get("/api/v1/admin/products/" + UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, admin))
			.andExpect(status().isNotFound());
	}

	// ------------------------------------------------------------------ helpers

	private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
			String bearer, String json) throws Exception {
		return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer)
			.contentType(MediaType.APPLICATION_JSON)
			.content(json));
	}

	private static String productJson(UUID categoryId, String slug, String price, String status) {
		String statusJson = status == null ? "" : ",\"status\":\"" + status + "\"";
		return """
				{"categoryId":"%s","name":"Fudge Brownie Box","slug":"%s","basePrice":%s,"minLeadHours":24,"productionUnits":2%s}"""
			.formatted(categoryId, slug, price, statusJson);
	}

	private int count(String table) {
		Integer count = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
		return count == null ? 0 : count;
	}

}
