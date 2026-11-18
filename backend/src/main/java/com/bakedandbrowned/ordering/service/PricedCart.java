package com.bakedandbrowned.ordering.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.SortedMap;
import java.util.UUID;

import com.bakedandbrowned.common.value.Money;
import com.bakedandbrowned.common.value.Quantity;
import com.bakedandbrowned.customization.service.ValidatedCustomization;
import com.bakedandbrowned.delivery.service.ZoneEligibility.EligibleZone;
import com.bakedandbrowned.scheduling.service.SlotService.SlotView;

/**
 * A cart priced and validated against current server data: everything a quote reports and an order
 * snapshots. Pricing (business rules): {@code unit = base + option deltas}, {@code line = quantity x
 * unit}, {@code subtotal = sum(lines)}, {@code total = subtotal + delivery fee - discount}, with the
 * discount always 0.00 in the MVP. Only addition and integer multiplication: no rounding.
 *
 * @param capacityUnits slot capacity this cart uses: sum of quantity x production units
 * @param ingredientNeeds stock this cart uses, in ascending ingredient-id order (lock order)
 * @param cancellationDeadline slot start minus the strictest item cutoff (ADR-0009)
 */
public record PricedCart(List<PricedLine> lines, Money subtotal, EligibleZone zone, Money discount, Money total,
		SlotView slot, int capacityUnits, SortedMap<UUID, Quantity> ingredientNeeds, Instant cancellationDeadline) {

	public PricedCart {
		lines = List.copyOf(lines);
	}

	public Money deliveryFee() {
		return zone.deliveryFee();
	}

	/** One priced cart line, with the product and customization details an order item snapshots. */
	public record PricedLine(UUID productId, String productName, Money unitPrice, int quantity, Money lineTotal,
			ValidatedCustomization customization, int productionUnits, int cancellationCutoffHours) {

		static PricedLine of(UUID productId, String productName, Money basePrice, int quantity,
				ValidatedCustomization customization, int productionUnits, int cancellationCutoffHours) {
			Money unit = basePrice.plus(customization.optionsTotal());
			return new PricedLine(productId, productName, unit, quantity, unit.times(quantity), customization,
					productionUnits, cancellationCutoffHours);
		}

	}

	static Money subtotal(List<PricedLine> lines) {
		return lines.stream().map(PricedLine::lineTotal).reduce(Money.ZERO, Money::plus);
	}

	static int capacityUnits(List<PricedLine> lines) {
		return lines.stream().mapToInt(l -> Math.multiplyExact(l.quantity(), l.productionUnits())).sum();
	}

	/** The strictest (largest) cutoff of all items wins for mixed orders. */
	static Instant cancellationDeadline(Instant slotStart, List<PricedLine> lines) {
		int strictest = lines.stream().mapToInt(PricedLine::cancellationCutoffHours).max().orElse(0);
		return slotStart.minus(Duration.ofHours(strictest));
	}

}
