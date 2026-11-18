package com.bakedandbrowned.scheduling.dto;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.bakedandbrowned.scheduling.entity.DeliverySlot;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** API shapes for delivery slots. */
public final class SlotDtos {

	private SlotDtos() {
	}

	/** Contract {@code DeliverySlot}: remaining units are informative, not a guarantee. */
	public record SlotResponse(UUID id, Instant startAt, Instant endAt, int remainingUnits) {

		public static SlotResponse from(DeliverySlot s) {
			return new SlotResponse(s.getId(), s.getStartAt(), s.getEndAt(), s.getRemainingUnits());
		}

	}

	/** Contract {@code AdminDeliverySlot}. */
	public record AdminSlotResponse(UUID id, Instant startAt, Instant endAt, int remainingUnits, int capacityUnits,
			int reservedUnits, boolean active) {

		public static AdminSlotResponse from(DeliverySlot s) {
			return new AdminSlotResponse(s.getId(), s.getStartAt(), s.getEndAt(), s.getRemainingUnits(),
					s.getCapacityUnits(), s.getReservedUnits(), s.isActive());
		}

	}

	/** Contract {@code DeliverySlotWrite}. */
	public record SlotWrite(@NotNull Instant startAt, @NotNull Instant endAt, @NotNull @Min(1) Integer capacityUnits,
			@Nullable Boolean active) {

		public boolean activeOrDefault() {
			return active == null || active;
		}

	}

	/** Contract {@code DeliverySlotUpdate}. */
	public record SlotUpdate(@NotNull @Min(1) Integer capacityUnits, @NotNull Boolean active) {
	}

}
