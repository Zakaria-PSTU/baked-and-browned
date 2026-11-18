package com.bakedandbrowned.ordering.service;

/**
 * Outbox event types the ordering module publishes (aggregate type {@code order}, aggregate id =
 * order id, payload {@code {"orderId": ...}}). Each needs exactly one {@code OutboxHandler}.
 */
public final class OrderEvents {

	public static final String AGGREGATE = "order";

	/** Order placed (COD confirmed, ONLINE/MANUAL awaiting payment). */
	public static final String PLACED = "order.placed";

	/** Payment verified: PENDING_PAYMENT → CONFIRMED. */
	public static final String CONFIRMED = "order.confirmed";

	/** Cancelled by the customer, or by an ADMIN (including a rejected manual payment). */
	public static final String CANCELLED = "order.cancelled";

	/** An ADMIN sent the order out: READY → OUT_FOR_DELIVERY. */
	public static final String DISPATCHED = "order.dispatched";

	/** Payment hold ran out. */
	public static final String EXPIRED = "order.expired";

	private OrderEvents() {
	}

}
