package com.bakedandbrowned.notification.sms.sslwireless;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import com.bakedandbrowned.common.readiness.ProductionReadinessCheck;
import com.bakedandbrowned.notification.sms.FakeSmsSender;
import com.bakedandbrowned.notification.sms.SmsSender;

import tools.jackson.databind.json.JsonMapper;

/**
 * The adapter exists only when enabled, then refuses to start without valid settings, and is then
 * the only SmsSender (the fake steps aside), in development as in production (ADR-0027).
 */
class SslWirelessWiringTest {

	private static final String[] SETTINGS = { "app.notifications.sms.ssl-wireless.enabled=true",
			"app.notifications.sms.ssl-wireless.base-url=https://smsplus.sslwireless.com/api/v3",
			"app.notifications.sms.ssl-wireless.api-token=test-token", "app.notifications.sms.ssl-wireless.sender-id=8809612345678",
			"app.notifications.sms.ssl-wireless.connect-timeout=5s", "app.notifications.sms.ssl-wireless.read-timeout=15s" };

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(SslWirelessConfiguration.class, FakeSmsSender.class)
		.withBean(JsonMapper.class, () -> JsonMapper.builder().build());

	@Test
	void offByDefault() {
		runner.withPropertyValues("spring.profiles.active=prod")
			.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(SmsSender.class));
		runner.withPropertyValues("spring.profiles.active=dev")
			.run(context -> assertThat(context).getBean(SmsSender.class).isInstanceOf(FakeSmsSender.class));
	}

	@Test
	void enabledInProductionWithItsSettings() {
		runner.withPropertyValues("spring.profiles.active=prod")
			.withPropertyValues(SETTINGS)
			.run(context -> assertThat(context).hasNotFailed()
				.getBean(SmsSender.class)
				.extracting(SmsSender::provider)
				.isEqualTo(SslWirelessSmsSender.PROVIDER));
	}

	/**
	 * The production readiness check (ADR-0030) refuses the SMS configurations that leave prod
	 * without a working sender, before the database is touched. If this wiring changes, this fails
	 * instead of the rule drifting.
	 */
	@Test
	void theReadinessCheckAgreesWithTheWiring() {
		// Off: no sender in prod, and the check says so.
		runner.withPropertyValues("spring.profiles.active=prod").run(context -> {
			assertThat(context).doesNotHaveBean(SmsSender.class);
			assertThat(smsProblems(context.getEnvironment())).hasSize(1).first().asString().contains("ssl-wireless.enabled");
		});
		// Enabled with its settings: one sender, and the check has nothing to say about SMS.
		runner.withPropertyValues("spring.profiles.active=prod").withPropertyValues(SETTINGS).run(context -> {
			assertThat(context).hasNotFailed().hasSingleBean(SmsSender.class);
			assertThat(smsProblems(context.getEnvironment())).isEmpty();
		});
		// Enabled without the token: the adapter would fail at startup, and the check says so first.
		runner.withPropertyValues("spring.profiles.active=prod", "app.notifications.sms.ssl-wireless.enabled=true",
				"app.notifications.sms.ssl-wireless.base-url=https://smsplus.sslwireless.com/api/v3",
				"app.notifications.sms.ssl-wireless.sender-id=8809612345678",
				"app.notifications.sms.ssl-wireless.connect-timeout=5s", "app.notifications.sms.ssl-wireless.read-timeout=15s")
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(smsProblems(new MockEnvironment()
					.withProperty("app.notifications.sms.ssl-wireless.enabled", "true")
					.withProperty("app.notifications.sms.ssl-wireless.sender-id", "8809612345678"))).hasSize(1)
					.first()
					.asString()
					.contains("api-token");
			});
	}

	private static List<String> smsProblems(Environment environment) {
		return ProductionReadinessCheck.problems(environment)
			.stream()
			.filter(problem -> problem.contains("ssl-wireless"))
			.toList();
	}

	@Test
	void enabledInDevelopmentReplacesTheFake() {
		runner.withPropertyValues("spring.profiles.active=dev")
			.withPropertyValues(SETTINGS)
			.run(context -> assertThat(context).hasNotFailed()
				.hasSingleBean(SmsSender.class)
				.doesNotHaveBean(FakeSmsSender.class)
				.getBean(SmsSender.class)
				.isInstanceOf(SslWirelessSmsSender.class));
	}

	@Test
	void enabledWithoutTheTokenFailsAtStartup() {
		runner.withPropertyValues("app.notifications.sms.ssl-wireless.enabled=true",
				"app.notifications.sms.ssl-wireless.base-url=https://smsplus.sslwireless.com/api/v3",
				"app.notifications.sms.ssl-wireless.sender-id=8809612345678",
				"app.notifications.sms.ssl-wireless.connect-timeout=5s",
				"app.notifications.sms.ssl-wireless.read-timeout=15s")
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("app.notifications.sms.ssl-wireless.api-token"));
	}

	@Test
	void plainHttpIsRefusedOutsideALocalStub() {
		runner.withPropertyValues(SETTINGS)
			.withPropertyValues("app.notifications.sms.ssl-wireless.base-url=http://smsplus.sslwireless.com/api/v3")
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("must use https"));
	}

	@Test
	void aSenderIdLongerThanDocumentedIsRefused() {
		runner.withPropertyValues(SETTINGS)
			.withPropertyValues("app.notifications.sms.ssl-wireless.sender-id=" + "9".repeat(21))
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("sender-id"));
	}

}
