package com.bakedandbrowned.payment.gateway.sslcommerz;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.bakedandbrowned.payment.gateway.PaymentGateway;

import tools.jackson.databind.json.JsonMapper;

/** The adapter exists only when enabled, and then refuses to start without its credentials. */
class SslCommerzWiringTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(SslCommerzConfiguration.class)
		.withBean(JsonMapper.class, () -> JsonMapper.builder().build());

	@Test
	void offByDefault() {
		runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(PaymentGateway.class));
	}

	@Test
	void enabledWithItsSettings() {
		runner
			.withPropertyValues("app.payments.sslcommerz.enabled=true",
					"app.payments.sslcommerz.base-url=https://sandbox.sslcommerz.com",
					"app.payments.sslcommerz.store-id=bakedtest01", "app.payments.sslcommerz.store-password=secret",
					"app.payments.sslcommerz.ipn-url=https://api.example.test/api/v1/payments/webhooks/sslcommerz",
					"app.payments.sslcommerz.fallback-customer-email=orders@example.test",
					"app.payments.sslcommerz.customer-city=Dhaka", "app.payments.sslcommerz.customer-postcode=1000",
					"app.payments.sslcommerz.customer-country=Bangladesh",
					"app.payments.sslcommerz.connect-timeout=5s", "app.payments.sslcommerz.read-timeout=20s")
			.run(context -> assertThat(context).hasNotFailed()
				.getBean(PaymentGateway.class)
				.extracting(PaymentGateway::provider)
				.isEqualTo(SslCommerzGateway.PROVIDER));
	}

	@Test
	void enabledWithoutCredentialsFailsAtStartup() {
		runner.withPropertyValues("app.payments.sslcommerz.enabled=true",
				"app.payments.sslcommerz.base-url=https://sandbox.sslcommerz.com")
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("app.payments.sslcommerz."));
	}

}
