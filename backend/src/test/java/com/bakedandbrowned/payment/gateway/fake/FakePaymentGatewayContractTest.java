package com.bakedandbrowned.payment.gateway.fake;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.bakedandbrowned.payment.gateway.GatewayPaymentStatus;
import com.bakedandbrowned.payment.gateway.PaymentGateway;
import com.bakedandbrowned.payment.gateway.PaymentGatewayContract;
import com.bakedandbrowned.payment.gateway.PaymentVerificationException;
import com.bakedandbrowned.payment.gateway.ProviderCallback;

import tools.jackson.databind.json.JsonMapper;

class FakePaymentGatewayContractTest extends PaymentGatewayContract {

	private final FakePaymentGateway fake = new FakePaymentGateway(new MockEnvironment(), JsonMapper.builder().build(),
			URI.create("http://localhost:4200/dev/fake-gateway"));

	@Override
	protected PaymentGateway gateway() {
		return fake;
	}

	@Override
	protected void settle(String sessionId, GatewayPaymentStatus outcome) {
		fake.settle(sessionId, outcome);
	}

	@Override
	protected ProviderCallback authenticCallback(String sessionId, GatewayPaymentStatus claimedStatus) {
		return fake.callbackFor(sessionId, claimedStatus);
	}

	@Test
	void refusesToStartUnderTheProdProfile() {
		MockEnvironment prod = new MockEnvironment();
		prod.setActiveProfiles("test", "prod");
		assertThatIllegalStateException().isThrownBy(
				() -> new FakePaymentGateway(prod, JsonMapper.builder().build(), URI.create("http://localhost")));
	}

	@Test
	void callbacksSignedByAnotherInstanceAreRejected() {
		FakePaymentGateway other = new FakePaymentGateway(new MockEnvironment(), JsonMapper.builder().build(),
				URI.create("http://localhost"));
		String session = fake.createSession(request("BB-2001", "100.00")).sessionId();

		ProviderCallback foreign = other.callbackFor(session, GatewayPaymentStatus.SUCCEEDED);
		assertThatThrownBy(() -> fake.verify(foreign)).isInstanceOf(PaymentVerificationException.class);
	}

	@Test
	void malformedSignedBodiesAreRejected() {
		ProviderCallback signed = fake.callbackFor("x", GatewayPaymentStatus.SUCCEEDED);
		// Correct signature header for a different body is still a forgery; a body without sessionId is malformed.
		assertThatThrownBy(() -> fake.verify(new ProviderCallback("fake", "application/json", signed.headers(), "{}")))
			.isInstanceOf(PaymentVerificationException.class);
		assertThatThrownBy(() -> fake.verify(new ProviderCallback("fake", "application/json", Map.of(), "not json")))
			.isInstanceOf(PaymentVerificationException.class);
	}

}
