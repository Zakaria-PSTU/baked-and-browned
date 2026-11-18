package com.bakedandbrowned.payment.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.bakedandbrowned.IntegrationTest;
import com.bakedandbrowned.payment.gateway.fake.FakePaymentGateway;

import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class PaymentGatewayWiringIntegrationTests {

	@Autowired
	private List<PaymentGateway> gateways;

	@Test
	void theTestProfileWiresOnlyTheFakeGateway() {
		assertThat(gateways).singleElement().isInstanceOf(FakePaymentGateway.class);
	}

	@Test
	void theFakeIsOnlyRegisteredUnderDevOrTest() {
		ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(FakePaymentGateway.class)
			.withBean(JsonMapper.class, () -> JsonMapper.builder().build())
			.withPropertyValues("app.payments.fake.checkout-base-url=http://localhost");

		runner.withPropertyValues("spring.profiles.active=prod")
			.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(PaymentGateway.class));
		runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(PaymentGateway.class));
		runner.withPropertyValues("spring.profiles.active=dev")
			.run(context -> assertThat(context).hasSingleBean(PaymentGateway.class));
		// Misconfiguration: dev together with prod fails startup instead of faking payments in production.
		runner.withPropertyValues("spring.profiles.active=dev,prod").run(context -> assertThat(context).hasFailed());
	}

}
