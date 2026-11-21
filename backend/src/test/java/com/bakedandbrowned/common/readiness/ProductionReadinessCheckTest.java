package com.bakedandbrowned.common.readiness;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

/** ADR-0030: production refuses to start on local-development defaults, and says which. */
class ProductionReadinessCheckTest {

	/** The values a correctly configured production deployment supplies. */
	private static MockEnvironment ready() {
		return new MockEnvironment().withProperty("app.security.allowed-origins[0]", "https://bakedandbrowned.com.bd")
			.withProperty("app.security.secure-cookies", "true")
			.withProperty("app.security.jwt.allow-ephemeral-key", "false")
			.withProperty("app.payments.online.return-url", "https://bakedandbrowned.com.bd/orders/{orderId}/payment")
			.withProperty("app.payments.online.api-base-url", "https://api.bakedandbrowned.com.bd")
			.withProperty("app.payments.proof-storage.directory", "/var/lib/bakedandbrowned/payment-proofs")
			.withProperty("app.payments.sslcommerz.enabled", "true")
			.withProperty("app.payments.sslcommerz.base-url", "https://securepay.sslcommerz.com");
	}

	/** The local-development defaults shipped in application.yaml, plus the two switches turned the wrong way. */
	private static MockEnvironment developmentDefaults() {
		return new MockEnvironment().withProperty("app.security.allowed-origins[0]", "http://localhost:4200")
			.withProperty("app.security.secure-cookies", "false")
			.withProperty("app.security.jwt.allow-ephemeral-key", "true")
			.withProperty("app.payments.online.return-url", "http://localhost:4200/orders/{orderId}/payment")
			.withProperty("app.payments.online.api-base-url", "http://localhost:8080")
			.withProperty("app.payments.proof-storage.directory", "data/payment-proofs");
	}

	@Test
	void aProductionConfigurationIsReady() {
		assertThat(ProductionReadinessCheck.problems(ready())).isEmpty();
		assertThat(ProductionReadinessCheck.warnings(ready())).isEmpty();
		// Several origins are fine when all are public https.
		assertThat(ProductionReadinessCheck.problems(
				ready().withProperty("app.security.allowed-origins[1]", "https://www.bakedandbrowned.com.bd")))
			.isEmpty();
	}

	@Test
	void everyDevelopmentDefaultIsReportedInOneListThatNamesNoValues() {
		List<String> problems = ProductionReadinessCheck.problems(developmentDefaults());

		assertThat(problems).hasSize(6);
		assertThat(String.join("\n", problems)).contains("app.security.allowed-origins",
				"app.security.secure-cookies", "app.security.jwt.allow-ephemeral-key", "app.payments.online.return-url",
				"app.payments.online.api-base-url", "app.payments.proof-storage.directory")
			.doesNotContain("localhost:4200", "localhost:8080", "data/payment-proofs");
	}

	@Test
	void eachRuleStandsOnItsOwn() {
		assertThat(only("app.security.allowed-origins[0]", "https://localhost")).contains("allowed-origins");
		// One local origin among public ones is still refused.
		assertThat(ProductionReadinessCheck
			.problems(ready().withProperty("app.security.allowed-origins[1]", "http://127.0.0.1:4200"))).hasSize(1);
		assertThat(only("app.security.secure-cookies", "false")).contains("secure-cookies");
		assertThat(only("app.security.jwt.allow-ephemeral-key", "true")).contains("allow-ephemeral-key");
		assertThat(only("app.payments.online.return-url", "http://bakedandbrowned.com.bd/orders/{orderId}/payment"))
			.contains("return-url");
		assertThat(only("app.payments.online.api-base-url", "https://[::1]:8443")).contains("api-base-url");
		assertThat(only("app.payments.proof-storage.directory", "./proofs")).contains("proof-storage");
		// A missing value is as bad as a development one (the two switches default to safe values).
		assertThat(ProductionReadinessCheck.problems(new MockEnvironment())).hasSize(4);
	}

	@Test
	void anEnabledGatewayMustNotUseTheSandbox() {
		assertThat(only("app.payments.sslcommerz.base-url", "https://sandbox.sslcommerz.com")).contains("sslcommerz.base-url")
			.contains("sandbox")
			.doesNotContain("sandbox.sslcommerz.com");
		// The shipped default when no address is set at all.
		assertThat(only("app.payments.sslcommerz.base-url", "")).contains("sslcommerz.base-url");
		// A disabled gateway is not checked: its default address is never called.
		assertThat(ProductionReadinessCheck.problems(ready().withProperty("app.payments.sslcommerz.enabled", "false")
			.withProperty("app.payments.sslcommerz.base-url", "https://sandbox.sslcommerz.com"))).isEmpty();
	}

	@Test
	void cashOnDeliveryAloneIsAWarningNotARefusal() {
		MockEnvironment codOnly = ready().withProperty("app.payments.sslcommerz.enabled", "false");
		assertThat(ProductionReadinessCheck.problems(codOnly)).isEmpty();
		assertThat(ProductionReadinessCheck.warnings(codOnly)).singleElement()
			.asString()
			.contains("cash on delivery");
		// A manual receiving account is enough.
		assertThat(ProductionReadinessCheck.warnings(codOnly
			.withProperty("app.payments.manual.accounts[0].account-number", "01700000000"))).isEmpty();
	}

	@Test
	void theCheckRunsOnlyForTheProductionWebApplication() {
		ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(ProductionReadinessCheck.class)
			.withPropertyValues("app.security.allowed-origins[0]=http://localhost:4200");

		runner.withInitializer(c -> c.getEnvironment().setActiveProfiles("prod"))
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.hasMessageContaining("Production configuration is not ready")
				.hasMessageContaining("app.security.allowed-origins"));
		// The operator command serves no web traffic.
		runner.withInitializer(c -> c.getEnvironment().setActiveProfiles("prod", "provision-admin"))
			.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ProductionReadinessCheck.class));
		runner.withInitializer(c -> c.getEnvironment().setActiveProfiles("dev"))
			.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ProductionReadinessCheck.class));
	}

	private static String only(String property, String value) {
		List<String> problems = ProductionReadinessCheck.problems(ready().withProperty(property, value));
		assertThat(problems).as("problems with %s", property).hasSize(1);
		return problems.get(0);
	}

}
