package com.bakedandbrowned.ordering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import tools.jackson.databind.JsonNode;

/** {@code POST /orders} and order reads: snapshots, side effects, idempotency, rollback, visibility. */
class OrderPlacementIntegrationTests extends CheckoutIntegrationTest {

	// ------------------------------------------------------------------ placement

	@Test
	void codOrdersAreConfirmedWithConfirmedReservationsAndAPendingPayment() throws Exception {
		String customer = bearer("CUSTOMER");
		String body = order(UUID.randomUUID(), "COD", slot, lineWithNote(fudge, 2, note, " Shubho Jonmodin! ", twelve),
				line(cookies, 1));

		String response = place(customer, body).andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("CONFIRMED"))
			.andExpect(jsonPath("$.paymentStatus").value("PENDING"))
			.andExpect(jsonPath("$.paymentMethod").value("COD"))
			.andExpect(jsonPath("$.orderNumber").value(matchesPattern("^BB-\\d{8}-\\d{6}$")))
			.andExpect(jsonPath("$.expiresAt").isEmpty())
			.andExpect(jsonPath("$.cancellable").value(true))
			.andExpect(jsonPath("$.statusHistory[*].status", contains("CONFIRMED")))
			.andExpect(jsonPath("$.payment.method").value("COD"))
			.andExpect(jsonPath("$.payment.manualSubmissionStatus").isEmpty())
			.andExpect(jsonPath("$.delivery.zoneCode").value("DHANMONDI"))
			.andExpect(jsonPath("$.delivery.zoneName").value("Dhanmondi"))
			.andExpect(jsonPath("$.delivery.phone").value("+8801712345678"))
			.andExpect(jsonPath("$.delivery.slotStartAt").value(slotStart.toString()))
			// Lines are listed by product name.
			.andExpect(jsonPath("$.items[*].productName", contains("Choc Chip Cookies", "Fudge Brownie Box")))
			.andExpect(jsonPath("$.items[0].customizations").isEmpty())
			.andExpect(jsonPath("$.items[1].quantity").value(2))
			.andExpect(jsonPath("$.items[1].customizations[*].groupName", contains("Box size", "Gift note")))
			.andExpect(jsonPath("$.items[1].customizations[0].optionName").value("12 pieces"))
			.andExpect(jsonPath("$.items[1].customizations[1].textValue").value("Shubho Jonmodin!"))
			.andExpect(jsonPath("$.items[1].customizations[1].optionId").isEmpty())
			.andReturn()
			.getResponse()
			.getContentAsString();
		// (1250 + 600) x 2 + 600 = 4300, + 60 delivery.
		assertThat(response).contains("\"subtotal\":4300.00", "\"deliveryFee\":60.00", "\"discount\":0.00",
				"\"total\":4360.00", "\"unitBasePrice\":1250.00", "\"unitOptionsPrice\":600.00", "\"unitPrice\":1850.00",
				"\"lineTotal\":3700.00");
		JsonNode detail = json.readTree(response);
		// ADR-0009: deadline = slot start minus the strictest cutoff (both products default to 24 h).
		assertThat(Instant.parse(detail.get("cancellationDeadline").asString()))
			.isEqualTo(slotStart.minus(Duration.ofHours(24)));

		UUID orderId = UUID.fromString(detail.get("orderId").asString());
		assertThat(reservedUnits(slot)).isEqualTo(5); // 2 boxes x 2 units + 1 x 1
		assertThat(reservedStock(chocolate)).isEqualByComparingTo("200");
		assertThat(jdbc.queryForObject("SELECT status::text FROM slot_reservations WHERE order_id = ?", String.class,
				orderId)).isEqualTo("CONFIRMED");
		assertThat(jdbc.queryForList("SELECT status::text FROM inventory_reservations WHERE order_id = ?", String.class,
				orderId)).containsExactly("CONFIRMED");
		Map<String, Object> payment = jdbc.queryForMap(
				"SELECT method::text AS method, status::text AS status, amount FROM payments WHERE order_id = ?", orderId);
		assertThat(payment).containsEntry("method", "COD").containsEntry("status", "PENDING");
		assertThat(payment.get("amount")).isEqualTo(new java.math.BigDecimal("4360.00"));
		assertThat(jdbc.queryForMap(
				"SELECT from_status, to_status::text AS to_status, actor_type::text AS actor, actor_user_id FROM order_status_history WHERE order_id = ?",
				orderId))
			.containsEntry("from_status", null)
			.containsEntry("to_status", "CONFIRMED")
			.containsEntry("actor", "CUSTOMER")
			.containsEntry("actor_user_id", userIdOf(customer));
		assertThat(jdbc.queryForList("SELECT event_type FROM outbox_events WHERE aggregate_id = ?", String.class, orderId))
			.containsExactly("order.placed");
	}

	@Test
	void onlineAndManualOrdersHoldReservationsForFifteenMinutes() throws Exception {
		String customer = bearer("CUSTOMER");
		for (String method : new String[] { "ONLINE", "MANUAL" }) {
			Instant before = Instant.now();
			String response = place(customer, order(UUID.randomUUID(), method, slot, line(fudge, 1, six)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
				.andExpect(jsonPath("$.statusHistory[*].status", contains("PENDING_PAYMENT")))
				.andReturn()
				.getResponse()
				.getContentAsString();
			JsonNode detail = json.readTree(response);
			Instant expiresAt = Instant.parse(detail.get("expiresAt").asString());
			assertThat(expiresAt).isBetween(before.plus(Duration.ofMinutes(15)).minusSeconds(5),
					Instant.now().plus(Duration.ofMinutes(15)).plusSeconds(5));
			UUID orderId = UUID.fromString(detail.get("orderId").asString());
			assertThat(jdbc.queryForObject("SELECT status::text FROM slot_reservations WHERE order_id = ?", String.class,
					orderId)).isEqualTo("HELD");
			assertThat(jdbc.queryForList("SELECT status::text FROM inventory_reservations WHERE order_id = ?",
					String.class, orderId)).containsExactly("HELD");
		}
		assertThat(reservedUnits(slot)).isEqualTo(4);
	}

	@Test
	void snapshotsAreImmuneToLaterCatalogChanges() throws Exception {
		String customer = bearer("CUSTOMER");
		String response = place(customer, order(UUID.randomUUID(), "COD", slot, line(fudge, 1, twelve)))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		String orderId = json.readTree(response).get("orderId").asString();

		// Business rules acceptance 8: price and names change after purchase.
		jdbc.update("UPDATE products SET base_price = 9999.00, name = 'Renamed Box' WHERE id = ?", fudge);
		jdbc.update("UPDATE customization_options SET name = '12 jumbo', price_delta = 5000.00 WHERE id = ?", twelve);
		jdbc.update("UPDATE delivery_zones SET delivery_fee = 500.00, name = 'Dhanmondi R/A' WHERE code = 'DHANMONDI'");

		String after = mockMvc.perform(get("/api/v1/orders/" + orderId).header(HttpHeaders.AUTHORIZATION, customer))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].productName").value("Fudge Brownie Box"))
			.andExpect(jsonPath("$.items[0].customizations[0].optionName").value("12 pieces"))
			.andExpect(jsonPath("$.delivery.zoneName").value("Dhanmondi"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(after).contains("\"unitPrice\":1850.00", "\"deliveryFee\":60.00", "\"total\":1910.00");
	}

	@Test
	void requiresASignedInUserAndValidInput() throws Exception {
		String body = order(UUID.randomUUID(), "COD", slot, line(cookies, 1));
		mockMvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isUnauthorized());
		String customer = bearer("CUSTOMER");
		place(customer, body.replace("\"paymentMethod\":\"COD\"", "\"paymentMethod\":\"CHEQUE\""))
			.andExpect(status().isBadRequest());
		place(customer, body.replace("\"idempotencyKey\":\"", "\"ignored\":\"")).andExpect(status().isUnprocessableContent());
		assertThat(count("orders")).isZero();
	}

	// ------------------------------------------------------------------ idempotency

	@Test
	void replayingTheSameRequestReturnsTheOriginalOrderAndCreatesNothing() throws Exception {
		String customer = bearer("CUSTOMER");
		UUID key = UUID.randomUUID();
		String first = place(customer, order(key, "COD", slot, line(fudge, 1, six), line(cookies, 2)))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		// Business rules acceptance 3: same key, same body. Prices changed meanwhile: replays never re-price.
		jdbc.update("UPDATE products SET base_price = 1.00 WHERE id = ?", cookies);
		String second = place(customer, order(key, "COD", slot, line(fudge, 1, six), line(cookies, 2)))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(json.readTree(second).get("orderId")).isEqualTo(json.readTree(first).get("orderId"));
		assertThat(second).contains("\"subtotal\":2450.00");
		assertThat(count("orders")).isEqualTo(1);
		assertThat(count("payments")).isEqualTo(1);
		assertThat(count("outbox_events")).isEqualTo(1);
		assertThat(reservedUnits(slot)).isEqualTo(4);
		assertThat(reservedStock(chocolate)).isEqualByComparingTo("100");
	}

	@Test
	void optionOrderDoesNotChangeTheRequestFingerprint() throws Exception {
		UUID toppings = group("TOPPINGS", "Toppings", "MULTIPLE", 0, 2);
		UUID walnut = option(toppings, "Walnuts", "80.00", true);
		UUID caramel = option(toppings, "Caramel", "120.00", true);
		assign(cookies, toppings, false, null, walnut, caramel);
		String customer = bearer("CUSTOMER");
		UUID key = UUID.randomUUID();
		place(customer, order(key, "COD", slot, line(cookies, 1, walnut, caramel))).andExpect(status().isCreated());
		place(customer, order(key, "COD", slot, line(cookies, 1, caramel, walnut))).andExpect(status().isCreated());
		assertThat(count("orders")).isEqualTo(1);
	}

	@Test
	void reusingAKeyForADifferentOrderIsRejected() throws Exception {
		String customer = bearer("CUSTOMER");
		UUID key = UUID.randomUUID();
		place(customer, order(key, "COD", slot, line(cookies, 1))).andExpect(status().isCreated());

		// Business rules acceptance 16: different cart, method or address with the same key.
		for (String changed : new String[] { order(key, "COD", slot, line(cookies, 2)),
				order(key, "MANUAL", slot, line(cookies, 1)),
				order(key, "COD", slot, line(cookies, 1)).replace("House 12", "House 99") }) {
			place(customer, changed).andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
		}
		assertThat(count("orders")).isEqualTo(1);
		assertThat(reservedUnits(slot)).isEqualTo(1);

		// Keys are per user: another customer may use the same key for their own order.
		place(bearer("CUSTOMER"), order(key, "COD", slot, line(cookies, 2))).andExpect(status().isCreated());
		assertThat(count("orders")).isEqualTo(2);
	}

	// ------------------------------------------------------------------ rollback

	@Test
	void losingTheStockRaceRollsBackEverything() throws Exception {
		// Only enough chocolate for 3 boxes left; the order wants 4. Pricing passes (it does not check
		// stock), so the shortfall is found while reserving, after the slot units were already taken.
		jdbc.update("UPDATE ingredients SET reserved = 650 WHERE id = ?", chocolate);
		place(bearer("CUSTOMER"), order(UUID.randomUUID(), "COD", slot, line(fudge, 4, six)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));

		assertNothingWritten();
		assertThat(reservedUnits(slot)).isZero();
		assertThat(reservedStock(chocolate)).isEqualByComparingTo("650");
	}

	@Test
	void losingTheCapacityRaceRollsBackEverything() throws Exception {
		jdbc.update("UPDATE delivery_slots SET reserved_units = 9 WHERE id = ?", slot);
		place(bearer("CUSTOMER"), order(UUID.randomUUID(), "COD", slot, line(fudge, 1, six)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SLOT_FULL"));

		assertNothingWritten();
		assertThat(reservedUnits(slot)).isEqualTo(9);
		assertThat(reservedStock(chocolate)).isEqualByComparingTo("0");
	}

	@Test
	void validationFailuresWriteNothing() throws Exception {
		String customer = bearer("CUSTOMER");
		place(customer, order(UUID.randomUUID(), "COD", slot, line(fudge, 1))) // box size is required
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("SELECTION_COUNT"));
		place(customer, order(UUID.randomUUID(), "COD", slot, line(cookies, 1)).replace("DHANMONDI", "MIRPUR"))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("ZONE_NOT_AVAILABLE"));
		assertNothingWritten();
		// A failed attempt does not burn the key: the corrected request with it succeeds.
		UUID key = UUID.randomUUID();
		place(customer, order(key, "COD", slot, line(fudge, 1))).andExpect(status().isUnprocessableContent());
		place(customer, order(key, "COD", slot, line(fudge, 1, six))).andExpect(status().isCreated());
	}

	// ------------------------------------------------------------------ reads and visibility

	@Test
	void customersSeeOnlyTheirOwnOrders() throws Exception {
		String alice = bearer("CUSTOMER");
		String bob = bearer("CUSTOMER");
		String first = orderId(place(alice, order(UUID.randomUUID(), "COD", slot, line(cookies, 1))));
		String second = orderId(place(alice, order(UUID.randomUUID(), "ONLINE", slot, line(cookies, 2))));
		orderId(place(bob, order(UUID.randomUUID(), "COD", slot, line(cookies, 3))));

		mockMvc.perform(get("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, alice))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[*].orderId", contains(second, first)))
			.andExpect(jsonPath("$[0].paymentStatus").value("PENDING"))
			.andExpect(jsonPath("$[0].items").doesNotExist());
		// Business rules acceptance 4: someone else's order is indistinguishable from a missing one.
		for (String id : new String[] { first, UUID.randomUUID().toString() }) {
			mockMvc.perform(get("/api/v1/orders/" + id).header(HttpHeaders.AUTHORIZATION, bob))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"))
				.andExpect(jsonPath("$.detail").value("Order not found."));
		}
		mockMvc.perform(get("/api/v1/orders/" + first).header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
			.andExpect(status().isOk());
	}

	@Test
	void ordersAreNotCancellableOnceTheDeadlineHasPassed() throws Exception {
		String customer = bearer("CUSTOMER");
		String id = orderId(place(customer, order(UUID.randomUUID(), "COD", slot, line(cookies, 1))));
		jdbc.update("UPDATE orders SET cancellation_deadline = now() - interval '1 second' WHERE id = ?::uuid", id);
		mockMvc.perform(get("/api/v1/orders/" + id).header(HttpHeaders.AUTHORIZATION, customer))
			.andExpect(jsonPath("$.cancellable").value(false));
	}

	@Test
	void placementIsRateLimitedPerUser() throws Exception {
		String customer = bearer("CUSTOMER");
		String body = order(UUID.randomUUID(), "COD", slot, line(cookies, 1));
		for (int i = 0; i < 20; i++) {
			place(customer, body).andExpect(status().isCreated()); // replays count too
		}
		place(customer, body).andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("RATE_LIMITED"));
		place(bearer("CUSTOMER"), body).andExpect(status().isCreated());
	}

	// ------------------------------------------------------------------ helpers

	private String orderId(org.springframework.test.web.servlet.ResultActions result) throws Exception {
		return json.readTree(result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
			.get("orderId")
			.asString();
	}

	private void assertNothingWritten() {
		for (String table : new String[] { "orders", "order_items", "order_item_customizations", "payments",
				"slot_reservations", "inventory_reservations", "order_status_history", "outbox_events" }) {
			assertThat(count(table)).as(table).isZero();
		}
	}

}
