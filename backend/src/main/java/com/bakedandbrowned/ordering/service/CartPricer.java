package com.bakedandbrowned.ordering.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bakedandbrowned.catalog.service.ProductCatalog;
import com.bakedandbrowned.catalog.service.ProductCatalog.PurchasableProduct;
import com.bakedandbrowned.common.value.Money;
import com.bakedandbrowned.common.value.Quantity;
import com.bakedandbrowned.common.web.ApiException;
import com.bakedandbrowned.customization.service.ProductCustomizationService;
import com.bakedandbrowned.delivery.service.DeliveryZoneService;
import com.bakedandbrowned.delivery.service.ZoneEligibility.EligibleZone;
import com.bakedandbrowned.inventory.service.RecipeService;
import com.bakedandbrowned.ordering.dto.CheckoutDtos.CartLine;
import com.bakedandbrowned.ordering.dto.CheckoutDtos.CheckoutQuoteRequest;
import com.bakedandbrowned.ordering.service.PricedCart.PricedLine;
import com.bakedandbrowned.scheduling.service.LeadTime;
import com.bakedandbrowned.scheduling.service.SlotService;
import com.bakedandbrowned.scheduling.service.SlotService.SlotView;

/**
 * Prices and validates a cart against current server data; shared by quote and checkout. Checks, in
 * order, each failing with 422: products purchasable, customizations valid, zone eligible for the
 * subtotal, slot open, every product's lead time met. Capacity and stock are reported here
 * ({@link PricedCart#capacityUnits()}, {@link PricedCart#ingredientNeeds()}) but decided by the
 * caller: the quote checks availability, checkout reserves.
 */
@Service
public class CartPricer {

	public static final String PRODUCT_UNAVAILABLE = "PRODUCT_UNAVAILABLE";

	public static final String SLOT_UNAVAILABLE = "SLOT_UNAVAILABLE";

	public static final String LEAD_TIME_NOT_MET = "LEAD_TIME_NOT_MET";

	private final ProductCatalog catalog;

	private final ProductCustomizationService customizations;

	private final DeliveryZoneService zones;

	private final SlotService slots;

	private final RecipeService recipes;

	public CartPricer(ProductCatalog catalog, ProductCustomizationService customizations, DeliveryZoneService zones,
			SlotService slots, RecipeService recipes) {
		this.catalog = catalog;
		this.customizations = customizations;
		this.zones = zones;
		this.slots = slots;
		this.recipes = recipes;
	}

	@Transactional(readOnly = true)
	public PricedCart price(CheckoutQuoteRequest request, Instant now) {
		Map<UUID, PurchasableProduct> products = catalog
			.purchasable(request.items().stream().map(CartLine::productId).distinct().toList());

		List<PricedLine> lines = new ArrayList<>();
		Map<UUID, Integer> quantities = new LinkedHashMap<>();
		for (CartLine item : request.items()) {
			PurchasableProduct product = products.get(item.productId());
			if (product == null) {
				throw ApiException.unprocessable(PRODUCT_UNAVAILABLE, "A product in your cart is no longer available.");
			}
			lines.add(PricedLine.of(product.id(), product.name(), product.basePrice(), item.quantity(),
					customizations.validateSelection(product.id(), item.selectedOptionIds(), item.customTexts()),
					product.productionUnits(), product.cancellationCutoffHours()));
			quantities.merge(product.id(), item.quantity(), Integer::sum);
		}

		Money subtotal = PricedCart.subtotal(lines);
		EligibleZone zone = zones.eligibleZone(request.delivery().area(), subtotal);
		Money discount = Money.ZERO;
		Money total = subtotal.plus(zone.deliveryFee()).minus(discount);

		SlotView slot = slots.find(request.deliverySlotId())
			.filter(s -> s.bookableAt(now))
			.orElseThrow(() -> ApiException.unprocessable(SLOT_UNAVAILABLE,
					"This delivery slot is no longer available. Please choose another."));
		for (PurchasableProduct product : products.values()) {
			if (!LeadTime.satisfied(now, slot.startAt(), product.minLeadHours())) {
				throw ApiException.unprocessable(LEAD_TIME_NOT_MET, "\"" + product.name() + "\" needs at least "
						+ product.minLeadHours() + " hours' notice. Please choose a later slot.");
			}
		}

		SortedMap<UUID, Quantity> needs = recipes.requirements(quantities);
		return new PricedCart(lines, subtotal, zone, discount, total, slot, PricedCart.capacityUnits(lines), needs,
				PricedCart.cancellationDeadline(slot.startAt(), lines));
	}

}
