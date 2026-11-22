package com.bakedandbrowned.common.readiness;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.KeyPairGenerator;
import java.util.Base64;
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
			.withProperty("app.payments.sslcommerz.base-url", "https://securepay.sslcommerz.com")
			.withProperty("app.security.jwt.private-key", PEM)
			.withProperty("app.notifications.sms.ssl-wireless.enabled", "true")
			.withProperty("app.notifications.sms.ssl-wireless.api-token", SMS_TOKEN)
			.withProperty("app.notifications.sms.ssl-wireless.sender-id", "BAKEDBROWN");
	}

	private static final String SMS_TOKEN = "sms-token-not-real";

	/** A real throwaway key, so the rule's parsing is exercised. */
	private static final String PEM = pkcs8Pem();

	private static String pkcs8Pem() {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			String body = Base64.getMimeEncoder(64, "\n".getBytes())
				.encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
			return "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n";
		}
		catch (java.security.GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
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

		assertThat(problems).hasSize(8);
		assertThat(String.join("\n", problems)).contains("app.security.allowed-origins",
				"app.security.secure-cookies", "app.security.jwt.allow-ephemeral-key", "app.payments.online.return-url",
				"app.payments.online.api-base-url", "app.payments.proof-storage.directory", "app.security.jwt.private-key",
				"app.notifications.sms.ssl-wireless.enabled")
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
		assertThat(ProductionReadinessCheck.problems(new MockEnvironment())).hasSize(6);
	}

	@Test
	void theSigningKeyMustBePresentAndParse() {
		assertThat(only("app.security.jwt.private-key", "")).contains("app.security.jwt.private-key");
		assertThat(only("app.security.jwt.private-key", "   ")).contains("app.security.jwt.private-key");
		// Garbage and PKCS#1 are refused, and the message repeats none of the text.
		String garbage = only("app.security.jwt.private-key",
				"-----BEGIN PRIVATE KEY-----\nNOTAKEYNOTAKEY\n-----END PRIVATE KEY-----");
		assertThat(garbage).contains("app.security.jwt.private-key").doesNotContain("NOTAKEYNOTAKEY");
		assertThat(only("app.security.jwt.private-key",
				"-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----"))
			.contains("app.security.jwt.private-key");
		// A real key passes and is never echoed.
		assertThat(ProductionReadinessCheck.problems(ready())).isEmpty();
	}

	@Test
	void theSmsSenderMustBeTheEnabledRealAdapterWithItsSettings() {
		assertThat(only("app.notifications.sms.ssl-wireless.enabled", "false"))
			.contains("app.notifications.sms.ssl-wireless.enabled");
		// Not set at all is the shipped default (off).
		assertThat(ProductionReadinessCheck.problems(ready().withProperty("app.notifications.sms.ssl-wireless.enabled", "")))
			.hasSize(1);
		assertThat(only("app.notifications.sms.ssl-wireless.api-token", " ")).contains("api-token")
			.doesNotContain(SMS_TOKEN);
		assertThat(only("app.notifications.sms.ssl-wireless.sender-id", "")).contains("sender-id");
		// A disabled adapter needs no token or sender id: one problem, not three.
		assertThat(ProductionReadinessCheck.problems(ready().withProperty("app.notifications.sms.ssl-wireless.enabled", "false")
			.withProperty("app.notifications.sms.ssl-wireless.api-token", "")
			.withProperty("app.notifications.sms.ssl-wireless.sender-id", ""))).hasSize(1);
	}

	@Test
	void prodMustNotBeCombinedWithDevOrTest() {
		for (String other : List.of("dev", "test")) {
			MockEnvironment env = ready();
			env.setActiveProfiles("prod", other);
			assertThat(ProductionReadinessCheck.problems(env)).singleElement()
				.asString()
				.contains("spring.profiles.active")
				.contains(other);
		}
		MockEnvironment prodOnly = ready();
		prodOnly.setActiveProfiles("prod");
		assertThat(ProductionReadinessCheck.problems(prodOnly)).isEmpty();
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
