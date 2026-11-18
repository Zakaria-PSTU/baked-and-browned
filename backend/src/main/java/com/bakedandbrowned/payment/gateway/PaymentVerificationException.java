package com.bakedandbrowned.payment.gateway;

/**
 * A callback or status query could not be verified (forged, malformed, unknown session, provider
 * unreachable). The message is for logs only and must not contain payment payloads.
 */
public class PaymentVerificationException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public PaymentVerificationException(String message) {
		super(message);
	}

	public PaymentVerificationException(String message, Throwable cause) {
		super(message, cause);
	}

}
