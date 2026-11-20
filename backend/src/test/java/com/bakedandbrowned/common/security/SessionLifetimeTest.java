package com.bakedandbrowned.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** ADR-0025: session lifetimes are positive, idle never exceeds absolute, and both are required. */
class SessionLifetimeTest {

	@Test
	void acceptsIdleUpToAbsolute() {
		assertThatNoException().isThrownBy(() -> new SecurityProperties.SessionLifetime(Duration.ofHours(8),
				Duration.ofHours(12)));
		assertThatNoException().isThrownBy(() -> new SecurityProperties.SessionLifetime(Duration.ofHours(12),
				Duration.ofHours(12)));
	}

	@Test
	void rejectsIdleLongerThanAbsolute() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new SecurityProperties.SessionLifetime(Duration.ofDays(31), Duration.ofDays(30)))
			.withMessageContaining("must not exceed");
	}

	@Test
	void rejectsZeroNegativeAndMissingLifetimes() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new SecurityProperties.SessionLifetime(Duration.ZERO, Duration.ofDays(30)));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new SecurityProperties.SessionLifetime(Duration.ofDays(-1), Duration.ofDays(30)));
		assertThatNullPointerException().isThrownBy(() -> new SecurityProperties.SessionLifetime(null, Duration.ofDays(30)));
		assertThatNullPointerException().isThrownBy(() -> new SecurityProperties.Sessions(
				new SecurityProperties.SessionLifetime(Duration.ofDays(14), Duration.ofDays(30)), null));
	}

	@Test
	void startupFailsOnAnInvalidConfiguration() {
		ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class)
			.withPropertyValues("app.security.jwt.issuer=iss", "app.security.jwt.audience=aud",
					"app.security.jwt.access-token-ttl=15m", "app.security.jwt.key-id=kid",
					"app.security.sessions.customer.idle=14d", "app.security.sessions.customer.absolute=30d",
					"app.security.sessions.admin.absolute=12h");

		runner.withPropertyValues("app.security.sessions.admin.idle=8h")
			.run(context -> assertThat(context).hasNotFailed());
		runner.withPropertyValues("app.security.sessions.admin.idle=13h")
			.run(context -> assertThat(context).hasFailed());
		runner.run(context -> assertThat(context).as("admin idle missing").hasFailed());
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(SecurityProperties.class)
	static class Config {

	}

}
