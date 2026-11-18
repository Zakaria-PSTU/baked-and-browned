package com.bakedandbrowned.scheduling.service;

import java.time.Duration;
import java.time.Instant;

/**
 * Lead-time rule (business rules, Delivery scheduling): a product with {@code minLeadHours} can be
 * delivered in a slot only if the slot starts at least that long after now. Evaluated per product;
 * a cart is deliverable in a slot only if every product passes.
 */
public final class LeadTime {

	private LeadTime() {
	}

	public static boolean satisfied(Instant now, Instant slotStart, int minLeadHours) {
		return !slotStart.isBefore(now.plus(Duration.ofHours(minLeadHours)));
	}

}
