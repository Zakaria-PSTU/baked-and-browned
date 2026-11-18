package com.bakedandbrowned.payment.gateway;

/** Provider-side state of a hosted session, normalized across providers. */
public enum GatewayPaymentStatus {

	/** Not finished yet (customer still on the page, or the provider is processing). */
	PENDING,

	SUCCEEDED,

	FAILED,

	/** The customer abandoned or cancelled the hosted page. */
	CANCELLED

}
