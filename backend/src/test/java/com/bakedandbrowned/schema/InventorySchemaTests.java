package com.bakedandbrowned.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Ingredient stock, reservations and the movement ledger. */
class InventorySchemaTests extends AbstractSchemaTest {

	@Test
	void guardedIngredientReservationNeverOversells() {
		UUID flour = ingredient(new BigDecimal("1000.000"));
		String reserve = "UPDATE ingredients SET reserved = reserved + ? WHERE id = ? AND on_hand - reserved >= ?";

		assertThat(jdbc.update(reserve, new BigDecimal("600"), flour, new BigDecimal("600"))).isEqualTo(1);
		assertThat(jdbc.update(reserve, new BigDecimal("600"), flour, new BigDecimal("600"))).isZero();
		assertThat(jdbc.update(reserve, new BigDecimal("400"), flour, new BigDecimal("400"))).isEqualTo(1);
	}

	@Test
	void onHandCannotDropBelowReserved() {
		UUID sugar = ingredient(new BigDecimal("500.000"));
		jdbc.update("UPDATE ingredients SET reserved = 300 WHERE id = ?", sugar);
		assertRejectedWith(CHECK_VIOLATION, "ingredients",
				() -> jdbc.update("UPDATE ingredients SET on_hand = 299 WHERE id = ?", sugar));
	}

	@Test
	void consumptionIsRecordedOncePerOrderAndIngredient() {
		UUID butter = ingredient(new BigDecimal("100.000"));
		UUID orderId = order(user(), "COD", "PREPARING");
		String consume = """
				INSERT INTO inventory_movements (ingredient_id, movement_type, quantity_delta, on_hand_after,
				                                 order_id, actor_type)
				VALUES (?, 'CONSUMPTION', -10.000, 90.000, ?, 'SYSTEM')""";

		jdbc.update(consume, butter, orderId);
		assertRejectedBy("uq_inventory_consumption_once", () -> jdbc.update(consume, butter, orderId));
	}

	@Test
	void adjustmentRequiresReasonAndAdmin() {
		UUID cocoa = ingredient(new BigDecimal("50.000"));
		UUID adminId = admin();
		String adjust = """
				INSERT INTO inventory_movements (ingredient_id, movement_type, quantity_delta, on_hand_after,
				                                 actor_type, actor_user_id, reason)
				VALUES (?, 'ADJUSTMENT', 5.000, 55.000, 'ADMIN', ?, ?)""";

		assertRejectedWith(CHECK_VIOLATION, "inventory_movements", () -> jdbc.update(adjust, cocoa, adminId, null));
		jdbc.update(adjust, cocoa, adminId, "stock count 2026-11-17");
	}

	@Test
	void movementsAreAppendOnly() {
		UUID eggs = ingredient(new BigDecimal("30.000"));
		UUID movement = jdbc.queryForObject("""
				INSERT INTO inventory_movements (ingredient_id, movement_type, quantity_delta, on_hand_after, actor_type)
				VALUES (?, 'INITIAL', 30.000, 30.000, 'SYSTEM') RETURNING id""", UUID.class, eggs);

		assertAppendOnly("inventory_movements",
				() -> jdbc.update("UPDATE inventory_movements SET quantity_delta = 1 WHERE id = ?", movement));
		assertAppendOnly("inventory_movements",
				() -> jdbc.update("DELETE FROM inventory_movements WHERE id = ?", movement));
	}

	@Test
	void reservationCannotBeBothReleasedAndConsumed() {
		UUID milk = ingredient(new BigDecimal("10.000"));
		UUID orderId = order(user(), "COD", "CONFIRMED");
		UUID reservation = jdbc.queryForObject("""
				INSERT INTO inventory_reservations (order_id, ingredient_id, quantity, status, consumed_at)
				VALUES (?, ?, 2.000, 'CONSUMED', now()) RETURNING id""", UUID.class, orderId, milk);

		assertRejectedBy("ck_inv_release_xor_consume",
				() -> jdbc.update("UPDATE inventory_reservations SET released_at = now() WHERE id = ?", reservation));
	}

}
