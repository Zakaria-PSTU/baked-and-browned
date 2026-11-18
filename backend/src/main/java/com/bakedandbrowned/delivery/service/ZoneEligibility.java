package com.bakedandbrowned.delivery.service;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.bakedandbrowned.common.value.Money;
import com.bakedandbrowned.common.web.ApiException;
import com.bakedandbrowned.delivery.entity.DeliveryZone;

/**
 * Whether an order may be delivered to a zone (ADR-0010): the zone exists, is active, has a fee,
 * and the order subtotal meets its minimum order amount (if set). Otherwise 422. Pure, so every
 * case is unit-tested. The fee always comes from the zone; nothing the client sends is read.
 */
public final class ZoneEligibility {

	/** Unknown, inactive or fee-less: the customer cannot choose this area (indistinguishable on purpose). */
	public static final String ZONE_NOT_AVAILABLE = "ZONE_NOT_AVAILABLE";

	public static final String BELOW_MINIMUM_ORDER = "BELOW_MINIMUM_ORDER";

	private ZoneEligibility() {
	}

	/** What an order snapshots from the zone: id, name (as {@code orders.delivery_area}) and fee. */
	public record EligibleZone(UUID id, String code, String name, Money deliveryFee) {
	}

	public static EligibleZone check(@Nullable DeliveryZone zone, Money subtotal) {
		Money fee = zone == null ? null : zone.getDeliveryFee();
		if (zone == null || !zone.isActive() || fee == null) {
			throw ApiException.unprocessable(ZONE_NOT_AVAILABLE, "We do not deliver to this area yet.");
		}
		Money minimum = zone.getMinOrderAmount();
		if (minimum != null && subtotal.compareTo(minimum) < 0) {
			throw ApiException.unprocessable(BELOW_MINIMUM_ORDER,
					"The minimum order for " + zone.getName() + " is " + minimum.toDisplayString() + ".");
		}
		return new EligibleZone(zone.getId(), zone.getCode(), zone.getName(), fee);
	}

}
