package com.bakedandbrowned.payment.gateway;

import java.net.URI;
import java.util.Objects;
import java.util.UUID;

import com.bakedandbrowned.common.value.Money;

/**
 * What a gateway needs to open a hosted checkout. The amount is the server-computed order total;
 * {@code returnUrl} is only where the customer's browser lands afterwards, never proof of payment.
 *
 * @param paymentId our {@code payments.id}, used as the provider-side idempotency/merchant reference
 * @param orderReference the customer-visible order number, echoed back in verified results
 */
public record PaymentSessionRequest(UUID paymentId, String orderReference, Money amount, URI returnUrl) {

	public PaymentSessionRequest {
		Objects.requireNonNull(paymentId, "paymentId");
		Objects.requireNonNull(orderReference, "orderReference");
		Objects.requireNonNull(amount, "amount");
		Objects.requireNonNull(returnUrl, "returnUrl");
		if (amount.isZero()) {
			throw new IllegalArgumentException("A gateway session needs a positive amount");
		}
	}

}
