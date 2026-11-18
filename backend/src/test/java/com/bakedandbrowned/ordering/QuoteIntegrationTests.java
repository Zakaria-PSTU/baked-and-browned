package com.bakedandbrowned.ordering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.bakedandbrowned.inventory.InventoryIntegrationTest;

/**
 * {@code POST /checkout/quote} against real data: server-side pricing, every rejection, and no side
 * effects.
 */
class QuoteIntegrationTests extends InventoryIntegrationTest {

	private String customer;

	private UUID fudge;

	private UUID cookies;

	private UUID size;

	private UUID twelve;

	private UUID six;

	private UUID slot;

	@BeforeEach
	void menu() {
		customer = bearer("CUSTOMER");
		UUID brownies = category("Brownies", "brownies", 1, true);
		fudge = product(brownies, "Fudge Brownie Box", "fudge-box", "1250.00", "ACTIVE");
		cookies = product(category("Cookies", "cookies", 2, true), "Choc Chip Cookies", "cookies-box", "600.00",
				"ACTIVE");
		size = group("BOX_SIZE", "Box size", "SINGLE", 1, 1);
		six = option(size, "6 pieces", "0.00", true);
		twelve = option(size, "12 pieces", "600.00", true);
		assign(fudge, size, true, null, six, twelve);
		jdbc.update("UPDATE products SET production_units = 2 WHERE id = ?", fudge);
		configureZone("DHANMONDI", "60.00", null, true);
		slot = slot(Instant.now().plus(Duration.ofDays(2)), Duration.ofHours(2), 10, 0, true);
	}

	@Test
	void quotesAreComputedOnTheServer() throws Exception {
		String body = quote(request(slot, "DHANMONDI", line(fudge, 2, twelve), line(cookies, 1)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.currency").value("BDT"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		// (1250 + 600) x 2 + 600 = 4300; + 60 delivery.
		assertThat(body).contains("\"subtotal\":4300.00", "\"deliveryFee\":60.00", "\"discount\":0.00",
				"\"total\":4360.00");
	}

	@Test
	void clientSuppliedPricesAndFeesAreIgnored() throws Exception {
		String tampered = request(slot, "DHANMONDI", line(cookies, 1))
			.replace("\"area\":\"DHANMONDI\"", "\"area\":\"DHANMONDI\",\"deliveryFee\":0")
			.replace("\"quantity\":1", "\"quantity\":1,\"unitPrice\":1.00,\"lineTotal\":1.00")
			.replace("{\"deliverySlotId\"", "{\"total\":1.00,\"discount\":600.00,\"deliverySlotId\"");
		String body = quote(tampered).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(body).contains("\"subtotal\":600.00", "\"deliveryFee\":60.00", "\"total\":660.00");
	}

	@Test
	void requiresASignedInUser() throws Exception {
		mockMvc.perform(post("/api/v1/checkout/quote").contentType(MediaType.APPLICATION_JSON)
			.content(request(slot, "DHANMONDI", line(cookies, 1)))).andExpect(status().isUnauthorized());
	}

	@Test
	void unavailableProductsAndBadCustomizationsAreRejected() throws Exception {
		UUID draft = product(category("Cakes", "cakes", 3, true), "Draft Cake", "draft-cake", "3000.00", "DRAFT");
		expect(request(slot, "DHANMONDI", line(draft, 1)), "PRODUCT_UNAVAILABLE");
		expect(request(slot, "DHANMONDI", line(UUID.randomUUID(), 1)), "PRODUCT_UNAVAILABLE");
		jdbc.update("UPDATE categories SET active = false WHERE slug = 'cookies'");
		expect(request(slot, "DHANMONDI", line(cookies, 1)), "PRODUCT_UNAVAILABLE");

		expect(request(slot, "DHANMONDI", line(fudge, 1)), "SELECTION_COUNT"); // box size is required
		expect(request(slot, "DHANMONDI", line(fudge, 1, six, twelve)), "SELECTION_COUNT");
		expect(request(slot, "DHANMONDI", line(fudge, 1, UUID.randomUUID())), "OPTION_NOT_ALLOWED");
	}

	@Test
	void theZoneMustBeEligibleForTheSubtotal() throws Exception {
		expect(request(slot, "MIRPUR", line(cookies, 1)), "ZONE_NOT_AVAILABLE");
		expect(request(slot, "NOWHERE", line(cookies, 1)), "ZONE_NOT_AVAILABLE");
		configureZone("DHANMONDI", "60.00", "1000.00", true);
		expect(request(slot, "DHANMONDI", line(cookies, 1)), "BELOW_MINIMUM_ORDER");
		quote(request(slot, "DHANMONDI", line(cookies, 2))).andExpect(status().isOk());
	}

	@Test
	void theSlotMustBeOpenAndLeaveEnoughLeadTime() throws Exception {
		UUID closed = slot(Instant.now().plus(Duration.ofDays(3)), Duration.ofHours(2), 10, 0, false);
		UUID started = slot(Instant.now().minusSeconds(60), Duration.ofHours(2), 10, 0, true);
		UUID tooSoon = slot(Instant.now().plus(Duration.ofHours(23)), Duration.ofHours(2), 10, 0, true);
		expect(request(closed, "DHANMONDI", line(cookies, 1)), "SLOT_UNAVAILABLE");
		expect(request(started, "DHANMONDI", line(cookies, 1)), "SLOT_UNAVAILABLE");
		expect(request(UUID.randomUUID(), "DHANMONDI", line(cookies, 1)), "SLOT_UNAVAILABLE");
		// Products default to 24 hours' notice.
		expect(request(tooSoon, "DHANMONDI", line(cookies, 1)), "LEAD_TIME_NOT_MET")
			.andExpect(jsonPath("$.detail").value("\"Choc Chip Cookies\" needs at least 24 hours' notice. Please choose a later slot."));
	}

	@Test
	void capacityAndStockAreCheckedWithoutReservingAnything() throws Exception {
		UUID small = slot(Instant.now().plus(Duration.ofDays(4)), Duration.ofHours(2), 5, 2, true);
		// Fudge uses 2 capacity units each: 2 boxes need 4, only 3 remain.
		expect(request(small, "DHANMONDI", line(fudge, 2, six)), "SLOT_FULL");
		quote(request(small, "DHANMONDI", line(fudge, 1, six))).andExpect(status().isOk());

		UUID chocolate = ingredient("Chocolate", "g", "500");
		jdbc.update("INSERT INTO product_ingredients (product_id, ingredient_id, quantity_per_unit) VALUES (?, ?, 120)",
				cookies, chocolate);
		quote(request(slot, "DHANMONDI", line(cookies, 4))).andExpect(status().isOk());
		expect(request(slot, "DHANMONDI", line(cookies, 5)), "OUT_OF_STOCK");
		// Two lines of the same product add up.
		expect(request(slot, "DHANMONDI", line(cookies, 3), line(cookies, 2)), "OUT_OF_STOCK");

		assertThat(column(chocolate, "reserved")).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(jdbc.queryForObject("SELECT sum(reserved_units) FROM delivery_slots", Integer.class)).isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isZero();
	}

	@Test
	void requestsAreValidated() throws Exception {
		for (String bad : List.of(request(slot, "DHANMONDI", line(cookies, 0)), request(slot, "DHANMONDI", line(cookies, 51)),
				request(slot, "DHANMONDI"), // no items
				request(slot, "DHANMONDI", line(cookies, 1)).replace("+8801712345678", "01712345678"),
				request(slot, "DHANMONDI", line(cookies, 1)).replace("House 12, Road 5", "Hm"),
				request(slot, "DHANMONDI", line(cookies, 1)).replace("\"customTexts\":{}",
						"\"customTexts\":{\"" + UUID.randomUUID() + "\":\"" + "x".repeat(251) + "\"}"))) {
			quote(bad).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		}
	}

	// ------------------------------------------------------------------ helpers

	private ResultActions quote(String json) throws Exception {
		return mockMvc.perform(post("/api/v1/checkout/quote").header(HttpHeaders.AUTHORIZATION, customer)
			.contentType(MediaType.APPLICATION_JSON)
			.content(json));
	}

	private ResultActions expect(String json, String code) throws Exception {
		return quote(json).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value(code));
	}

	private static String line(UUID product, int quantity, UUID... options) {
		String ids = String.join(",", java.util.Arrays.stream(options).map(o -> "\"" + o + "\"").toList());
		return "{\"productId\":\"%s\",\"quantity\":%d,\"selectedOptionIds\":[%s],\"customTexts\":{}}".formatted(product,
				quantity, ids);
	}

	private static String request(UUID slot, String area, String... lines) {
		return """
				{"deliverySlotId":"%s","delivery":{"name":"Nusrat Jahan","phone":"+8801712345678","area":"%s",\
				"address":"House 12, Road 5","notes":"Ring twice"},"items":[%s]}"""
			.formatted(slot, area, String.join(",", lines));
	}

}
