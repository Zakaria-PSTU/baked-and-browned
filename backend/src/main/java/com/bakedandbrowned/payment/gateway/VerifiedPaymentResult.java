package com.bakedandbrowned.payment.gateway;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.bakedandbrowned.common.value.Money;

/**
 * The provider's authoritative state of one session, obtained server to server.
 *
 * @param eventId stable key for this provider state, for {@code payment_webhook_events
 * (provider, event_id)}: duplicate callbacks for the same outcome share it
 * @param transactionId the provider's transaction id; present once the payment succeeded
 * @param amount and {@code currency}: as reported by the provider, to be compared with the payment
 */
public record VerifiedPaymentResult(String provider, String eventId, String sessionId, @Nullable String transactionId,
		String orderReference, Money amount, String currency, GatewayPaymentStatus status) {

	public VerifiedPaymentResult {
		Objects.requireNonNull(provider, "provider");
		Objects.requireNonNull(eventId, "eventId");
		Objects.requireNonNull(sessionId, "sessionId");
		Objects.requireNonNull(orderReference, "orderReference");
		Objects.requireNonNull(amount, "amount");
		Objects.requireNonNull(currency, "currency");
		Objects.requireNonNull(status, "status");
		if (status == GatewayPaymentStatus.SUCCEEDED && (transactionId == null || transactionId.isBlank())) {
			throw new IllegalArgumentException("A succeeded payment must carry the provider transaction id");
		}
	}

	/** True if this result may confirm a payment of {@code expectedAmount} BDT for {@code expectedReference}. */
	public boolean confirms(String expectedReference, Money expectedAmount) {
		return status == GatewayPaymentStatus.SUCCEEDED && orderReference.equals(expectedReference)
				&& amount.equals(expectedAmount) && "BDT".equals(currency);
	}

}
