package com.bakedandbrowned.payment.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.bakedandbrowned.common.value.Money;

/**
 * Behavior every {@link PaymentGateway} adapter must show (ADR-0012). The fake runs it now; the
 * SSLCommerz adapter must pass the same suite against its sandbox in Phase 3.
 * <p>
 * Subclasses provide the adapter and drive the provider side: settling a session (the customer
 * pays, fails or cancels on the hosted page) and producing the callback the provider would send.
 */
public abstract class PaymentGatewayContract {

	protected abstract PaymentGateway gateway();

	/** Provider side: the customer finishes the hosted checkout with {@code outcome}. */
	protected abstract void settle(String sessionId, GatewayPaymentStatus outcome);

	/** Provider side: the authentic callback for the session, claiming {@code claimedStatus}. */
	protected abstract ProviderCallback authenticCallback(String sessionId, GatewayPaymentStatus claimedStatus);

	/**
	 * Forged or tampered variants of an authentic callback that the adapter must reject. By default:
	 * the same body without its headers (a missing signature) and with the session id altered.
	 * Adapters whose authenticity lies elsewhere (SSLCommerz: the validation id) override this.
	 */
	protected java.util.List<ProviderCallback> forgeries(ProviderCallback authentic, String sessionId) {
		return java.util.List.of(
				new ProviderCallback(authentic.provider(), authentic.contentType(), java.util.Map.of(), authentic.body()),
				new ProviderCallback(authentic.provider(), authentic.contentType(), authentic.headers(),
						authentic.body().replace(sessionId, sessionId + "x")));
	}

	protected PaymentSessionRequest request(String reference, String amount) {
		return new PaymentSessionRequest(UUID.randomUUID(), reference, Money.of(amount),
				URI.create("https://bakedandbrowned.com.bd/orders/" + reference),
				new PaymentSessionRequest.PaymentCustomer("Nusrat Jahan", "+8801712345678", null, "House 12, Road 5",
						"Dhanmondi"));
	}

	@Test
	void sessionsAreDistinctAndPointAtAHostedPage() {
		HostedPaymentSession first = gateway().createSession(request("BB-1001", "1250.00"));
		HostedPaymentSession second = gateway().createSession(request("BB-1002", "1250.00"));

		assertThat(first.sessionId()).isNotEqualTo(second.sessionId());
		assertThat(first.checkoutUrl().isAbsolute()).isTrue();
	}

	@Test
	void newSessionsArePending() {
		String session = gateway().createSession(request("BB-1003", "980.00")).sessionId();

		VerifiedPaymentResult result = gateway().queryStatus(session);
		assertThat(result.status()).isEqualTo(GatewayPaymentStatus.PENDING);
		assertThat(result.transactionId()).isNull();
		assertThat(result.confirms("BB-1003", Money.of("980.00"))).isFalse();
	}

	@Test
	void verifiedSuccessCarriesTheProvidersReferenceAmountAndTransaction() {
		String session = gateway().createSession(request("BB-1004", "1250.00")).sessionId();
		settle(session, GatewayPaymentStatus.SUCCEEDED);

		VerifiedPaymentResult result = gateway().verify(authenticCallback(session, GatewayPaymentStatus.SUCCEEDED));

		assertThat(result.provider()).isEqualTo(gateway().provider());
		assertThat(result.sessionId()).isEqualTo(session);
		assertThat(result.status()).isEqualTo(GatewayPaymentStatus.SUCCEEDED);
		assertThat(result.transactionId()).isNotBlank();
		assertThat(result.confirms("BB-1004", Money.of("1250.00"))).isTrue();
		// The caller's amount/reference check is what stops a cheaper session confirming this order.
		assertThat(result.confirms("BB-1004", Money.of("1300.00"))).isFalse();
		assertThat(result.confirms("BB-9999", Money.of("1250.00"))).isFalse();
	}

	@Test
	void aCallbackClaimingSuccessCannotOverrideTheProviderState() {
		String session = gateway().createSession(request("BB-1005", "500.00")).sessionId();

		VerifiedPaymentResult result = gateway().verify(authenticCallback(session, GatewayPaymentStatus.SUCCEEDED));

		assertThat(result.status()).isEqualTo(GatewayPaymentStatus.PENDING);
	}

	@Test
	void failedAndCancelledSessionsNeverConfirm() {
		for (GatewayPaymentStatus outcome : new GatewayPaymentStatus[] { GatewayPaymentStatus.FAILED,
				GatewayPaymentStatus.CANCELLED }) {
			String session = gateway().createSession(request("BB-1006", "500.00")).sessionId();
			settle(session, outcome);

			VerifiedPaymentResult result = gateway().verify(authenticCallback(session, outcome));
			assertThat(result.status()).isEqualTo(outcome);
			assertThat(result.confirms("BB-1006", Money.of("500.00"))).isFalse();
		}
	}

	@Test
	void duplicateCallbacksResolveToTheSameEventId() {
		String session = gateway().createSession(request("BB-1007", "750.00")).sessionId();
		settle(session, GatewayPaymentStatus.SUCCEEDED);

		VerifiedPaymentResult first = gateway().verify(authenticCallback(session, GatewayPaymentStatus.SUCCEEDED));
		VerifiedPaymentResult second = gateway().verify(authenticCallback(session, GatewayPaymentStatus.SUCCEEDED));

		assertThat(second.eventId()).isEqualTo(first.eventId());
		assertThat(second.transactionId()).isEqualTo(first.transactionId());
	}

	@Test
	void forgedOrTamperedCallbacksAreRejected() {
		String session = gateway().createSession(request("BB-1008", "750.00")).sessionId();
		ProviderCallback authentic = authenticCallback(session, GatewayPaymentStatus.SUCCEEDED);

		for (ProviderCallback forged : forgeries(authentic, session)) {
			assertThatThrownBy(() -> gateway().verify(forged)).isInstanceOf(PaymentVerificationException.class);
		}
	}

	@Test
	void unknownSessionsAreRejected() {
		assertThatThrownBy(() -> gateway().queryStatus("no-such-session"))
			.isInstanceOf(PaymentVerificationException.class);
	}

}
