package com.bakedandbrowned.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import com.bakedandbrowned.scheduling.service.SlotCapacity;

/** Delivery slots: Dhaka-day listing, admin management, and atomic capacity. */
class DeliverySlotIntegrationTests extends DeliveryIntegrationTest {

	private static final Duration TWO_HOURS = Duration.ofHours(2);

	@Autowired
	private SlotCapacity capacity;

	@Autowired
	private TransactionTemplate transactions;

	// ------------------------------------------------------------------ public listing

	@Test
	void listsBookableSlotsOfOneDhakaDay() throws Exception {
		LocalDate day = dhakaDate(3);
		UUID morning = slot(at(day, "10:00"), TWO_HOURS, 10, 2, true);
		UUID earlyMidnight = slot(at(day, "00:30"), TWO_HOURS, 5, 0, true); // 18:30 UTC the day before
		slot(at(day, "14:00"), TWO_HOURS, 5, 5, true); // full
		slot(at(day, "16:00"), TWO_HOURS, 5, 0, false); // closed
		slot(at(day.plusDays(1), "00:00"), TWO_HOURS, 5, 0, true); // next Dhaka day
		slot(at(day.minusDays(1), "23:59"), TWO_HOURS, 5, 0, true); // previous Dhaka day

		mockMvc.perform(get("/api/v1/delivery-slots").param("date", day.toString()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[*].id", contains(earlyMidnight.toString(), morning.toString())))
			.andExpect(jsonPath("$[1].remainingUnits").value(8))
			.andExpect(jsonPath("$[1].startAt").value(at(day, "10:00").toString()))
			.andExpect(jsonPath("$[1].capacityUnits").doesNotExist())
			.andExpect(jsonPath("$[1].active").doesNotExist());
	}

	@Test
	void slotsThatHaveStartedAreNotBookable() throws Exception {
		// Started a minute ago, still running, plenty of capacity: not offered any more.
		Instant startedAt = Instant.now().minusSeconds(60);
		UUID started = slot(startedAt, TWO_HOURS, 5, 0, true);
		LocalDate day = LocalDate.ofInstant(startedAt, com.bakedandbrowned.common.time.BusinessTime.ZONE);

		String body = mockMvc.perform(get("/api/v1/delivery-slots").param("date", day.toString()))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(body).doesNotContain(started.toString());
	}

	@Test
	void theDateParameterIsRequiredAndMustBeADate() throws Exception {
		mockMvc.perform(get("/api/v1/delivery-slots")).andExpect(status().isBadRequest());
		mockMvc.perform(get("/api/v1/delivery-slots").param("date", "18-11-2026")).andExpect(status().isBadRequest());
	}

	// ------------------------------------------------------------------ admin

	@Test
	void adminCreatesFutureSlotsOnly() throws Exception {
		String admin = bearer("ADMIN");
		Instant start = at(dhakaDate(2), "10:00");

		send(post("/api/v1/admin/delivery-slots"), admin, slotJson(start, start.plus(TWO_HOURS), 12))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.capacityUnits").value(12))
			.andExpect(jsonPath("$.reservedUnits").value(0))
			.andExpect(jsonPath("$.remainingUnits").value(12))
			.andExpect(jsonPath("$.active").value(true));
		send(post("/api/v1/admin/delivery-slots"), admin, slotJson(start, start.plus(TWO_HOURS), 3))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SLOT_EXISTS"));
		send(post("/api/v1/admin/delivery-slots"), admin, slotJson(start, start, 3))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("INVALID_SLOT"));
		Instant past = Instant.now().minusSeconds(3600);
		send(post("/api/v1/admin/delivery-slots"), admin, slotJson(past, past.plus(TWO_HOURS), 3))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("SLOT_IN_PAST"));
		send(post("/api/v1/admin/delivery-slots"), admin, slotJson(start.plus(TWO_HOURS), start.plus(TWO_HOURS).plus(TWO_HOURS), 0))
			.andExpect(status().isUnprocessableContent());
		assertThat(jdbc.queryForList("SELECT action FROM audit_events", String.class))
			.containsExactly("DELIVERY_SLOT_CREATED");
	}

	@Test
	void capacityCannotDropBelowReservedAndClosingKeepsReservations() throws Exception {
		String admin = bearer("ADMIN");
		UUID id = slot(at(dhakaDate(2), "10:00"), TWO_HOURS, 10, 6, true);

		send(patch("/api/v1/admin/delivery-slots/" + id), admin, "{\"capacityUnits\":5,\"active\":true}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CAPACITY_BELOW_RESERVED"));
		send(patch("/api/v1/admin/delivery-slots/" + id), admin, "{\"capacityUnits\":6,\"active\":false}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.capacityUnits").value(6))
			.andExpect(jsonPath("$.reservedUnits").value(6))
			.andExpect(jsonPath("$.remainingUnits").value(0))
			.andExpect(jsonPath("$.active").value(false));
		send(patch("/api/v1/admin/delivery-slots/" + UUID.randomUUID()), admin, "{\"capacityUnits\":6,\"active\":true}")
			.andExpect(status().isNotFound());

		assertThat(jdbc.queryForObject("SELECT capacity_units FROM delivery_slots WHERE id = ?", Integer.class, id))
			.isEqualTo(6);
		assertThat(jdbc.queryForList("SELECT action FROM audit_events", String.class))
			.containsExactly("DELIVERY_SLOT_UPDATED");
	}

	@Test
	void adminListingCoversDhakaDaysInclusiveWithinSixtyTwoDays() throws Exception {
		String admin = bearer("ADMIN");
		LocalDate from = dhakaDate(1);
		slot(at(from, "00:00"), TWO_HOURS, 5, 0, false);
		slot(at(from.plusDays(2), "23:00"), TWO_HOURS, 5, 5, true);
		slot(at(from.plusDays(3), "00:00"), TWO_HOURS, 5, 0, true);

		mockMvc.perform(get("/api/v1/admin/delivery-slots").param("from", from.toString())
			.param("to", from.plusDays(2).toString())
			.header(HttpHeaders.AUTHORIZATION, admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(2)))
			.andExpect(jsonPath("$[0].active").value(false))
			.andExpect(jsonPath("$[1].reservedUnits").value(5));
		mockMvc.perform(get("/api/v1/admin/delivery-slots").param("from", from.toString())
			.param("to", from.plusDays(63).toString())
			.header(HttpHeaders.AUTHORIZATION, admin))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("INVALID_RANGE"));
		mockMvc.perform(get("/api/v1/admin/delivery-slots").param("from", from.toString())
			.param("to", from.minusDays(1).toString())
			.header(HttpHeaders.AUTHORIZATION, admin)).andExpect(status().isUnprocessableContent());
	}

	// ------------------------------------------------------------------ capacity primitives

	@Test
	void onlyOneOfManyConcurrentCustomersGetsTheLastUnit() throws Exception {
		UUID id = slot(at(dhakaDate(2), "10:00"), TWO_HOURS, 5, 4, true);
		int customers = 8;
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Boolean>> results = new ArrayList<>();
		try (ExecutorService pool = Executors.newFixedThreadPool(customers)) {
			for (int i = 0; i < customers; i++) {
				results.add(pool.submit(() -> {
					start.await();
					return transactions.execute(tx -> capacity.tryReserve(id, 1, Instant.now()));
				}));
			}
			start.countDown();
			int won = 0;
			for (Future<Boolean> result : results) {
				if (result.get(30, TimeUnit.SECONDS)) {
					won++;
				}
			}
			assertThat(won).isEqualTo(1);
		}
		assertThat(reserved(id)).isEqualTo(5);
	}

	@Test
	void reservationsRespectStateAndReleasesCannotGoNegative() {
		UUID open = slot(at(dhakaDate(2), "10:00"), TWO_HOURS, 5, 0, true);
		UUID closed = slot(at(dhakaDate(2), "12:00"), TWO_HOURS, 5, 0, false);
		UUID started = slot(Instant.now().minusSeconds(60), TWO_HOURS, 5, 0, true);

		assertThat(inTx(() -> capacity.tryReserve(open, 3, Instant.now()))).isTrue();
		assertThat(inTx(() -> capacity.tryReserve(open, 3, Instant.now()))).isFalse();
		assertThat(inTx(() -> capacity.tryReserve(closed, 1, Instant.now()))).isFalse();
		assertThat(inTx(() -> capacity.tryReserve(started, 1, Instant.now()))).isFalse();
		assertThat(inTx(() -> capacity.tryReserve(UUID.randomUUID(), 1, Instant.now()))).isFalse();

		transactions.executeWithoutResult(tx -> capacity.release(open, 2));
		assertThat(reserved(open)).isEqualTo(1);
		assertThatIllegalStateException()
			.isThrownBy(() -> transactions.executeWithoutResult(tx -> capacity.release(open, 2)));
		assertThat(reserved(open)).isEqualTo(1);
	}

	// ------------------------------------------------------------------ helpers

	private boolean inTx(java.util.function.Supplier<Boolean> work) {
		return Boolean.TRUE.equals(transactions.execute(tx -> work.get()));
	}

	private int reserved(UUID id) {
		Integer units = jdbc.queryForObject("SELECT reserved_units FROM delivery_slots WHERE id = ?", Integer.class, id);
		return units == null ? -1 : units;
	}

	private ResultActions send(MockHttpServletRequestBuilder request, String bearer, String json) throws Exception {
		return mockMvc.perform(
				request.header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private static String slotJson(Instant start, Instant end, int capacity) {
		return "{\"startAt\":\"%s\",\"endAt\":\"%s\",\"capacityUnits\":%d}".formatted(start, end, capacity);
	}

}
