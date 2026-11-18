package com.bakedandbrowned.ordering;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.bakedandbrowned.common.web.ApiException;
import com.bakedandbrowned.ordering.dto.OrderDtos.PlaceOrderRequest;
import com.bakedandbrowned.ordering.service.OrderPlacementService;

/**
 * Concurrent checkouts through the real service and PostgreSQL: duplicate requests, and races for
 * the last slot capacity and the last stock. Each attempt runs in its own thread and transaction.
 */
class OrderConcurrencyIntegrationTests extends CheckoutIntegrationTest {

	private static final int THREADS = 6;

	@Autowired
	private OrderPlacementService placement;

	@Test
	void concurrentDuplicatesOfOneRequestCreateExactlyOneOrder() throws Exception {
		UUID customer = userIdOf(bearer("CUSTOMER"));
		PlaceOrderRequest request = parse(order(UUID.randomUUID(), "COD", slot, line(fudge, 1, six), line(cookies, 1)));

		List<Outcome> outcomes = race(THREADS, i -> () -> attempt(customer, request));

		// Every caller gets the same order (the loser of the unique-key race recovers the winner's).
		assertThat(outcomes).allSatisfy(o -> assertThat(o.error()).isNull());
		assertThat(outcomes.stream().map(Outcome::orderId).collect(Collectors.toSet())).hasSize(1);
		assertThat(count("orders")).isEqualTo(1);
		assertThat(count("payments")).isEqualTo(1);
		assertThat(count("slot_reservations")).isEqualTo(1);
		assertThat(count("outbox_events")).isEqualTo(1);
		assertThat(reservedUnits(slot)).isEqualTo(3);
		assertThat(reservedStock(chocolate)).isEqualByComparingTo("100");
	}

	@Test
	void onlyOneCustomerGetsTheLastSlotCapacity() throws Exception {
		// Business rules acceptance 2: 2 units left, each order needs 2.
		jdbc.update("UPDATE delivery_slots SET reserved_units = 8 WHERE id = ?", slot);
		List<UUID> customers = customers(THREADS);

		List<Outcome> outcomes = race(THREADS, i -> () -> attempt(customers.get(i),
				parse(order(UUID.randomUUID(), "COD", slot, line(fudge, 1, six)))));

		assertThat(outcomes).filteredOn(o -> o.error() == null).hasSize(1);
		assertThat(outcomes).filteredOn(o -> o.error() != null)
			.hasSize(THREADS - 1)
			.allSatisfy(o -> assertThat(o.error()).isEqualTo("SLOT_FULL"));
		assertThat(reservedUnits(slot)).isEqualTo(10);
		assertThat(count("orders")).isEqualTo(1);
		// The losers' rolled-back attempts left no stock reserved.
		assertThat(reservedStock(chocolate)).isEqualByComparingTo("100");
	}

	@Test
	void onlyOneCustomerGetsTheLastStock() throws Exception {
		// Business rules acceptance 1: chocolate for exactly one more box.
		jdbc.update("UPDATE ingredients SET reserved = 900 WHERE id = ?", chocolate);
		List<UUID> customers = customers(THREADS);

		List<Outcome> outcomes = race(THREADS, i -> () -> attempt(customers.get(i),
				parse(order(UUID.randomUUID(), "ONLINE", slot, line(fudge, 1, six)))));

		assertThat(outcomes).filteredOn(o -> o.error() == null).hasSize(1);
		assertThat(outcomes).filteredOn(o -> o.error() != null)
			.allSatisfy(o -> assertThat(o.error()).isEqualTo("INSUFFICIENT_STOCK"));
		assertThat(reservedStock(chocolate)).isEqualByComparingTo("1000");
		assertThat(count("orders")).isEqualTo(1);
		// Slot units taken by the losers before they hit the stock shortfall were rolled back.
		assertThat(reservedUnits(slot)).isEqualTo(2);
	}

	// ------------------------------------------------------------------ helpers

	private record Outcome(UUID orderId, String error) {
	}

	private Outcome attempt(UUID customer, PlaceOrderRequest request) {
		try {
			return new Outcome(placement.place(customer, request).orderId(), null);
		}
		catch (ApiException ex) {
			return new Outcome(null, ex.code());
		}
	}

	private List<UUID> customers(int n) {
		List<UUID> ids = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			ids.add(userIdOf(bearer("CUSTOMER")));
		}
		return ids;
	}

	private static List<Outcome> race(int threads, java.util.function.IntFunction<Callable<Outcome>> task)
			throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Outcome>> futures = new ArrayList<>();
		try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
			for (int i = 0; i < threads; i++) {
				Callable<Outcome> work = task.apply(i);
				futures.add(pool.submit(() -> {
					start.await();
					return work.call();
				}));
			}
			start.countDown();
			List<Outcome> outcomes = new ArrayList<>();
			for (Future<Outcome> future : futures) {
				outcomes.add(future.get(60, TimeUnit.SECONDS));
			}
			return outcomes;
		}
	}

}
