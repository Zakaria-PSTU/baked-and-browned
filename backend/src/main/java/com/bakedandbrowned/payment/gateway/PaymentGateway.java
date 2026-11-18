package com.bakedandbrowned.payment.gateway;

/**
 * Provider-neutral port to a hosted-payment-page gateway (ADR-0012). Adapters own provider
 * specifics: request formats, callback parsing (JSON or form-encoded) and authenticity checks.
 * <p>
 * Rules for every implementation and caller:
 * <ul>
 * <li>Calls may do network I/O: never invoke them inside a database transaction or while holding
 * row locks. Apply the result afterwards in a separate short transaction that locks the order.</li>
 * <li>A browser redirect is never proof of payment. Only a {@link VerifiedPaymentResult} is, and
 * adapters produce one only after a server-to-server status or validation call.</li>
 * <li>Callers must still check the result's order reference, amount and currency against the
 * payment they expect before changing any state.</li>
 * </ul>
 */
public interface PaymentGateway {

	/** Stable provider key, as used in {@code /payments/webhooks/{provider}} and {@code payments.provider}. */
	String provider();

	/** Starts a hosted checkout for one payment attempt. */
	HostedPaymentSession createSession(PaymentSessionRequest request);

	/**
	 * Authenticates a provider callback and resolves it to the provider's authoritative state.
	 * @throws PaymentVerificationException if the callback is forged, malformed or refers to an
	 * unknown session; nothing about the payment may be changed then
	 */
	VerifiedPaymentResult verify(ProviderCallback callback);

	/**
	 * Asks the provider for the current state of a session (reconciliation, delayed callbacks).
	 * @throws PaymentVerificationException if the provider does not know the session
	 */
	VerifiedPaymentResult queryStatus(String sessionId);

}
