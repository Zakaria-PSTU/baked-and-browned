package com.bakedandbrowned.payment.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Which adapter new ONLINE sessions use, and that a misconfiguration fails at startup. */
class PaymentGatewaysTest {

	private static PaymentGateway named(String provider) {
		return new PaymentGateway() {

			@Override
			public String provider() {
				return provider;
			}

			@Override
			public HostedPaymentSession createSession(PaymentSessionRequest request) {
				throw new UnsupportedOperationException();
			}

			@Override
			public VerifiedPaymentResult verify(ProviderCallback callback) {
				throw new UnsupportedOperationException();
			}

			@Override
			public VerifiedPaymentResult queryStatus(String sessionId) {
				throw new UnsupportedOperationException();
			}

		};
	}

	@Test
	void withoutAnyAdapterOnlinePaymentsAreUnavailable() {
		PaymentGateways gateways = new PaymentGateways(List.of(), "");
		assertThat(gateways.active()).isEmpty();
		assertThat(gateways.byProvider("fake")).isEmpty();
	}

	@Test
	void aSingleAdapterIsUsedWhenNoneIsNamed() {
		PaymentGateway only = named("fake");
		assertThat(new PaymentGateways(List.of(only), null).active()).containsSame(only);
		assertThat(new PaymentGateways(List.of(only), " ").active()).containsSame(only);
	}

	@Test
	void theNamedAdapterIsActiveAndEveryAdapterStillReceivesItsCallbacks() {
		PaymentGateway fake = named("fake");
		PaymentGateway other = named("sslcommerz");
		PaymentGateways gateways = new PaymentGateways(List.of(fake, other), "sslcommerz");
		assertThat(gateways.active()).containsSame(other);
		assertThat(gateways.byProvider("fake")).containsSame(fake);
	}

	@Test
	void ambiguousOrUnknownConfigurationFailsAtStartup() {
		assertThatThrownBy(() -> new PaymentGateways(List.of(named("a"), named("b")), ""))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("app.payments.online.provider");
		assertThatThrownBy(() -> new PaymentGateways(List.of(named("a")), "b"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("unknown gateway 'b'");
	}

}
