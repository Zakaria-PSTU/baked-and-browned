package com.bakedandbrowned.ordering;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.bakedandbrowned.inventory.InventoryIntegrationTest;
import com.bakedandbrowned.ordering.dto.OrderDtos.PlaceOrderRequest;

import tools.jackson.databind.json.JsonMapper;

/**
 * Checkout fixture: a brownie box (2 capacity units, required box size, optional gift note, 100 g of
 * chocolate each) and cookies (1 unit, no recipe), an active Dhanmondi zone (fee 60.00) and a slot
 * two days ahead with 10 units.
 */
abstract class CheckoutIntegrationTest extends InventoryIntegrationTest {

	@Autowired
	protected JsonMapper json;

	protected UUID fudge;

	protected UUID cookies;

	protected UUID six;

	protected UUID twelve;

	protected UUID note;

	protected UUID chocolate;

	protected UUID slot;

	protected Instant slotStart;

	@BeforeEach
	void menu() {
		// No foreign key ties events to orders, so they are not truncated with them.
		jdbc.execute("TRUNCATE outbox_events");
		UUID brownies = category("Brownies", "brownies", 1, true);
		fudge = product(brownies, "Fudge Brownie Box", "fudge-box", "1250.00", "ACTIVE");
		cookies = product(category("Cookies", "cookies", 2, true), "Choc Chip Cookies", "cookies-box", "600.00",
				"ACTIVE");
		jdbc.update("UPDATE products SET production_units = 2 WHERE id = ?", fudge);
		UUID size = group("BOX_SIZE", "Box size", "SINGLE", 1, 1);
		six = option(size, "6 pieces", "0.00", true);
		twelve = option(size, "12 pieces", "600.00", true);
		note = group("GIFT_NOTE", "Gift note", "TEXT", 0, 1);
		assign(fudge, size, true, null, six, twelve);
		assign(fudge, note, false, 40);
		chocolate = ingredient("Chocolate", "g", "1000");
		jdbc.update("INSERT INTO product_ingredients (product_id, ingredient_id, quantity_per_unit) VALUES (?, ?, 100)",
				fudge, chocolate);
		configureZone("DHANMONDI", "60.00", null, true);
		slotStart = Instant.now().plus(Duration.ofDays(2)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
		slot = slot(slotStart, Duration.ofHours(2), 10, 0, true);
	}

	protected ResultActions place(String bearer, String body) throws Exception {
		return mockMvc.perform(post("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, bearer)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	protected PlaceOrderRequest parse(String body) {
		return json.readValue(body, PlaceOrderRequest.class);
	}

	protected static String line(UUID product, int quantity, UUID... options) {
		String ids = String.join(",", Arrays.stream(options).map(o -> "\"" + o + "\"").toList());
		return "{\"productId\":\"%s\",\"quantity\":%d,\"selectedOptionIds\":[%s],\"customTexts\":{}}".formatted(product,
				quantity, ids);
	}

	protected static String lineWithNote(UUID product, int quantity, UUID noteGroup, String text, UUID... options) {
		return line(product, quantity, options).replace("\"customTexts\":{}",
				"\"customTexts\":{\"" + noteGroup + "\":\"" + text + "\"}");
	}

	protected static String order(UUID key, String method, UUID slot, String... lines) {
		return """
				{"idempotencyKey":"%s","paymentMethod":"%s","deliverySlotId":"%s","delivery":{"name":"Nusrat Jahan",\
				"phone":"+8801712345678","area":"DHANMONDI","address":"House 12, Road 5","notes":"Ring twice"},\
				"items":[%s]}""".formatted(key, method, slot, String.join(",", lines));
	}

	protected int count(String table) {
		Integer n = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
		return n == null ? 0 : n;
	}

	protected int reservedUnits(UUID slotId) {
		Integer n = jdbc.queryForObject("SELECT reserved_units FROM delivery_slots WHERE id = ?", Integer.class, slotId);
		return n == null ? -1 : n;
	}

	protected BigDecimal reservedStock(UUID ingredient) {
		return column(ingredient, "reserved");
	}

}
