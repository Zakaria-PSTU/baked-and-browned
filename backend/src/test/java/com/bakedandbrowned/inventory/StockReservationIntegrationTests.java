package com.bakedandbrowned.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import com.bakedandbrowned.common.value.Quantity;
import com.bakedandbrowned.common.web.ApiException;
import com.bakedandbrowned.inventory.service.RecipeService;
import com.bakedandbrowned.inventory.service.StockReservations;

/** Reserve / confirm / release / consume: atomic, ordered, and at most once. */
class StockReservationIntegrationTests extends InventoryIntegrationTest {

	@Autowired
	private StockReservations stock;

	@Autowired
	private RecipeService recipes;

	@Autowired
	private TransactionTemplate transactions;

	@Test
	void requirementsSumRecipesTimesQuantities() {
		UUID category = category("Brownies", "brownies", 1, true);
		UUID fudge = product(category, "Fudge", "fudge", "100.00", "ACTIVE");
		UUID walnut = product(category, "Walnut", "walnut", "100.00", "ACTIVE");
		UUID plain = product(category, "Plain", "plain", "100.00", "ACTIVE");
		UUID butter = ingredient("Butter", "g", "1000");
		UUID cocoa = ingredient("Cocoa", "g", "1000");
		recipe(fudge, butter, "50.000", cocoa, "30.250");
		recipe(walnut, butter, "40.000");

		TreeMap<UUID, Quantity> needed = recipes.requirements(Map.of(fudge, 2, walnut, 3, plain, 5));

		assertThat(needed).containsExactlyEntriesOf(new TreeMap<>(Map.of(butter, Quantity.of("220.000"), cocoa,
				Quantity.of("60.500"))));
		assertThat(needed.firstKey()).isEqualTo(butter.compareTo(cocoa) < 0 ? butter : cocoa);
	}

	@Test
	void reservingIsAllOrNothing() {
		UUID butter = ingredient("Butter", "g", "100");
		UUID cocoa = ingredient("Cocoa", "g", "10");
		UUID order = order();

		assertThatThrownBy(() -> transactions.executeWithoutResult(
				tx -> stock.reserve(order, needs(butter, "60", cocoa, "11"))))
			.isInstanceOfSatisfying(ApiException.class, ex -> {
				assertThat(ex.status().value()).isEqualTo(409);
				assertThat(ex.code()).isEqualTo(StockReservations.INSUFFICIENT_STOCK);
				assertThat(ex.getMessage()).doesNotContain("Cocoa");
			});
		assertThat(column(butter, "reserved")).isEqualByComparingTo("0");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_reservations", Integer.class)).isZero();

		transactions.executeWithoutResult(tx -> stock.reserve(order, needs(butter, "60", cocoa, "10")));
		assertThat(column(butter, "reserved")).isEqualByComparingTo("60");
		assertThat(column(cocoa, "reserved")).isEqualByComparingTo("10");
		assertThat(jdbc.queryForList("SELECT status::text FROM inventory_reservations", String.class))
			.containsOnly("HELD");
	}

	@Test
	void onlyOneOfTwoCustomersGetsTheLastStock() throws Exception {
		UUID chocolate = ingredient("Chocolate", "g", "100");
		UUID first = order();
		UUID second = order();

		List<Boolean> outcomes = race(() -> reserveOrFail(first, chocolate), () -> reserveOrFail(second, chocolate));

		assertThat(outcomes).containsExactlyInAnyOrder(true, false);
		assertThat(column(chocolate, "reserved")).isEqualByComparingTo("100");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_reservations", Integer.class)).isEqualTo(1);
	}

	@Test
	void releaseHappensOnceEvenWhenConcurrent() throws Exception {
		UUID butter = ingredient("Butter", "g", "100");
		UUID order = order();
		transactions.executeWithoutResult(tx -> stock.reserve(order, needs(butter, "40")));

		List<Integer> released = race(() -> transactions.execute(tx -> stock.release(order)),
				() -> transactions.execute(tx -> stock.release(order)));

		assertThat(released).containsExactlyInAnyOrder(1, 0);
		assertThat(column(butter, "reserved")).isEqualByComparingTo("0");
		assertThat(column(butter, "on_hand")).isEqualByComparingTo("100");
		assertThat(jdbc.queryForObject("SELECT status::text FROM inventory_reservations", String.class))
			.isEqualTo("RELEASED");
		assertThat(jdbc.queryForObject("SELECT released_at IS NOT NULL FROM inventory_reservations", Boolean.class))
			.isTrue();
	}

	@Test
	void consumptionDeductsStockOnceAndLedgersIt() {
		UUID butter = ingredient("Butter", "g", "100");
		UUID cocoa = ingredient("Cocoa", "g", "50");
		UUID order = order();
		UUID admin = userIdOf(bearer("ADMIN"));
		transactions.executeWithoutResult(tx -> {
			stock.reserve(order, needs(butter, "40", cocoa, "5.5"));
			assertThat(stock.confirm(order)).isEqualTo(2);
		});

		assertThat(inTx(() -> stock.consume(order, admin))).isEqualTo(2);
		assertThat(inTx(() -> stock.consume(order, admin))).isZero();
		// Consumed stock is never returned by a later release.
		assertThat(inTx(() -> stock.release(order))).isZero();

		assertThat(column(butter, "on_hand")).isEqualByComparingTo("60");
		assertThat(column(butter, "reserved")).isEqualByComparingTo("0");
		assertThat(column(cocoa, "on_hand")).isEqualByComparingTo("44.5");
		List<Map<String, Object>> ledger = jdbc.queryForList("""
				SELECT movement_type::text AS type, quantity_delta, on_hand_after, order_id, actor_type::text AS actor,
				       actor_user_id
				FROM inventory_movements ORDER BY on_hand_after""");
		assertThat(ledger).hasSize(2).allSatisfy(m -> {
			assertThat(m).containsEntry("type", "CONSUMPTION").containsEntry("order_id", order)
				.containsEntry("actor", "ADMIN")
				.containsEntry("actor_user_id", admin);
		});
		assertThat(ledger).extracting(m -> m.get("quantity_delta"))
			.containsExactly(new BigDecimal("-5.500"), new BigDecimal("-40.000"));
		assertThat(jdbc.queryForList("SELECT status::text FROM inventory_reservations", String.class))
			.containsOnly("CONSUMED");
	}

	@Test
	void adminAdjustmentsCannotUndercutReservations() {
		UUID butter = ingredient("Butter", "g", "100");
		UUID order = order();
		transactions.executeWithoutResult(tx -> stock.reserve(order, needs(butter, "70")));
		// The database check backs up the service's 409 for any other writer.
		assertThatThrownBy(() -> jdbc.update("UPDATE ingredients SET on_hand = 69 WHERE id = ?", butter))
			.hasMessageContaining("ingredients");
	}

	// ------------------------------------------------------------------ helpers

	private int inTx(java.util.function.IntSupplier work) {
		Integer result = transactions.execute(tx -> work.getAsInt());
		return result == null ? -1 : result;
	}

	private boolean reserveOrFail(UUID order, UUID ingredient) {
		try {
			transactions.executeWithoutResult(tx -> stock.reserve(order, needs(ingredient, "100")));
			return true;
		}
		catch (ApiException ex) {
			return false;
		}
	}

	@SafeVarargs
	private static <T> List<T> race(Callable<T>... tasks) throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		List<Future<T>> futures = new ArrayList<>();
		try (ExecutorService pool = Executors.newFixedThreadPool(tasks.length)) {
			for (Callable<T> task : tasks) {
				futures.add(pool.submit(() -> {
					start.await();
					return task.call();
				}));
			}
			start.countDown();
			List<T> results = new ArrayList<>();
			for (Future<T> future : futures) {
				results.add(future.get(30, TimeUnit.SECONDS));
			}
			return results;
		}
	}

	private static TreeMap<UUID, Quantity> needs(Object... idAndQuantity) {
		TreeMap<UUID, Quantity> needed = new TreeMap<>();
		for (int i = 0; i < idAndQuantity.length; i += 2) {
			needed.put((UUID) idAndQuantity[i], Quantity.of((String) idAndQuantity[i + 1]));
		}
		return needed;
	}

	private void recipe(UUID product, Object... ingredientAndPerUnit) {
		for (int i = 0; i < ingredientAndPerUnit.length; i += 2) {
			jdbc.update("INSERT INTO product_ingredients (product_id, ingredient_id, quantity_per_unit) VALUES (?, ?, ?)",
					product, ingredientAndPerUnit[i], new BigDecimal((String) ingredientAndPerUnit[i + 1]));
		}
	}

}
