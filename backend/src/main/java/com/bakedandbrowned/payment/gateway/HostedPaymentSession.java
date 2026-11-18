package com.bakedandbrowned.payment.gateway;

import java.net.URI;
import java.util.Objects;

/** A created hosted checkout: store {@code sessionId} on the payment, send the customer to {@code checkoutUrl}. */
public record HostedPaymentSession(String sessionId, URI checkoutUrl) {

	public HostedPaymentSession {
		Objects.requireNonNull(sessionId, "sessionId");
		Objects.requireNonNull(checkoutUrl, "checkoutUrl");
	}

}
