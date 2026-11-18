package com.bakedandbrowned.inventory.dto;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.bakedandbrowned.common.value.Quantity;
import com.bakedandbrowned.inventory.entity.Ingredient;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** API shapes for inventory administration (contract tag Admin). */
public final class InventoryDtos {

	private InventoryDtos() {
	}

	/** Contract {@code Ingredient}. */
	public record IngredientResponse(UUID id, String name, String unit, Quantity onHand, Quantity reserved,
			Quantity available, @Nullable Quantity lowStockThreshold) {

		public static IngredientResponse from(Ingredient i) {
			return new IngredientResponse(i.getId(), i.getName(), i.getUnit(), i.getOnHand(), i.getReserved(),
					i.getAvailable(), i.getLowStockThreshold());
		}

	}

	/** Contract {@code IngredientCreate}. */
	public record IngredientCreate(@NotBlank @Size(min = 2, max = 120) String name,
			@NotBlank @Size(max = 20) String unit, @Nullable Quantity onHand, @Nullable Quantity lowStockThreshold) {

		public Quantity onHandOrZero() {
			return onHand == null ? Quantity.ZERO : onHand;
		}

	}

	/** Body of {@code adminAdjustInventory}: the new on-hand quantity and why. */
	public record InventoryAdjustment(@NotNull Quantity onHand, @NotBlank @Size(min = 3, max = 300) String reason) {
	}

	/** Contract {@code InventoryMovement}. */
	public record MovementResponse(UUID id, UUID ingredientId, String movementType, java.math.BigDecimal quantityDelta,
			Quantity onHandAfter, @Nullable UUID orderId, String actorType, @Nullable UUID actorUserId,
			@Nullable String reason, Instant createdAt) {
	}

	/** Contract {@code ProductIngredientLine}; {@code ingredientName}/{@code unit} are output only. */
	public record RecipeLine(@NotNull UUID ingredientId, @Nullable String ingredientName, @Nullable String unit,
			@NotNull Quantity quantityPerUnit) {
	}

}
