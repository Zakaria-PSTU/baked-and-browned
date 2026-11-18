package com.bakedandbrowned.payment.service;

/** Outbox event types the payment module publishes. Each needs exactly one {@code OutboxHandler}. */
public final class PaymentEvents {

	/** Aggregate type of refund events; the aggregate id is the refund id. */
	public static final String REFUND_AGGREGATE = "refund";

	/** An ADMIN recorded that a refund was sent; payload {@code {"refundId", "orderId"}}. */
	public static final String REFUND_COMPLETED = "refund.completed";

	private PaymentEvents() {
	}

}
