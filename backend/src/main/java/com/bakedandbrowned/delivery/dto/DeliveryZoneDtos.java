package com.bakedandbrowned.delivery.dto;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.bakedandbrowned.common.value.Money;
import com.bakedandbrowned.delivery.entity.DeliveryZone;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** API shapes for delivery zones (ADR-0010). */
public final class DeliveryZoneDtos {

	private DeliveryZoneDtos() {
	}

	/** Contract {@code DeliveryZone}: a zone a customer may pick; {@code code} goes in {@code DeliveryAddress.area}. */
	public record DeliveryZoneResponse(String code, String name, Money deliveryFee, @Nullable Money minOrderAmount) {

		/** Only for selectable zones, which always have a fee. */
		public static DeliveryZoneResponse from(DeliveryZone z) {
			Money fee = z.getDeliveryFee();
			if (fee == null) {
				throw new IllegalStateException("Zone " + z.getCode() + " has no fee and is not selectable");
			}
			return new DeliveryZoneResponse(z.getCode(), z.getName(), fee, z.getMinOrderAmount());
		}

	}

	/** Contract {@code AdminDeliveryZone}. */
	public record AdminDeliveryZoneResponse(UUID id, String code, String name, @Nullable Money deliveryFee,
			@Nullable Money minOrderAmount, boolean active, int sortOrder) {

		public static AdminDeliveryZoneResponse from(DeliveryZone z) {
			return new AdminDeliveryZoneResponse(z.getId(), z.getCode(), z.getName(), z.getDeliveryFee(),
					z.getMinOrderAmount(), z.isActive(), z.getSortOrder());
		}

	}

	/** Contract {@code DeliveryZoneWrite}; full replacement on update. */
	public record DeliveryZoneWrite(@NotNull @Pattern(regexp = "^[A-Z0-9_]{2,60}$") String code,
			@NotBlank @Size(min = 2, max = 120) String name, @Nullable Money deliveryFee, @Nullable Money minOrderAmount,
			@NotNull Boolean active, @Nullable Integer sortOrder) {

		public int sortOrderOrDefault() {
			return sortOrder == null ? 0 : sortOrder;
		}

	}

}
