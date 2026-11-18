package com.bakedandbrowned.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.bakedandbrowned.common.web.RateLimitExceededException;

class RateLimiterTest {

	private final MutableClock clock = new MutableClock(Instant.parse("2026-11-18T06:00:00Z"));

	private final RateLimiter limiter = new RateLimiter(clock,
			new RateLimitProperties(Map.of("login", new RateLimitProperties.Policy(3, Duration.ofMinutes(5)))));

	@Test
	void allowsTheLimitThenRejectsWithRetryAfterUntilTheWindowEnds() {
		for (int i = 0; i < 3; i++) {
			limiter.check("login", "10.0.0.1");
		}
		clock.advance(Duration.ofSeconds(100));
		assertThatThrownBy(() -> limiter.check("login", "10.0.0.1")).isInstanceOfSatisfying(
				RateLimitExceededException.class, ex -> assertThat(ex.retryAfterSeconds()).isEqualTo(200));

		clock.advance(Duration.ofSeconds(200));
		limiter.check("login", "10.0.0.1");
	}

	@Test
	void keysAreIndependent() {
		for (int i = 0; i < 3; i++) {
			limiter.check("login", "10.0.0.1");
		}
		limiter.check("login", "10.0.0.2");
	}

	@Test
	void retryAfterIsRoundedUpAndAtLeastOneSecond() {
		for (int i = 0; i < 3; i++) {
			limiter.check("login", "k");
		}
		clock.advance(Duration.ofMinutes(5).minusMillis(1));
		assertThatThrownBy(() -> limiter.check("login", "k")).isInstanceOfSatisfying(RateLimitExceededException.class,
				ex -> assertThat(ex.retryAfterSeconds()).isEqualTo(1));
	}

	@Test
	void unknownPolicyIsAProgrammingError() {
		assertThatIllegalArgumentException().isThrownBy(() -> limiter.check("checkout", "k"));
	}

	@Test
	void expiredWindowsAreSweptOnceManyKeysAreTracked() {
		for (int i = 0; i <= RateLimiter.SWEEP_THRESHOLD; i++) {
			limiter.check("login", "ip-" + i);
		}
		clock.advance(Duration.ofMinutes(6));
		limiter.check("login", "fresh");
		assertThat(limiter.trackedKeys()).isEqualTo(1);
	}

	@Test
	void policiesRejectNonsense() {
		assertThatIllegalArgumentException().isThrownBy(() -> new RateLimitProperties.Policy(0, Duration.ofMinutes(1)));
		assertThatIllegalArgumentException().isThrownBy(() -> new RateLimitProperties.Policy(1, Duration.ZERO));
	}

	static final class MutableClock extends Clock {

		private Instant now;

		MutableClock(Instant now) {
			this.now = now;
		}

		void advance(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return ZoneId.of("Asia/Dhaka");
		}

		@Override
		public Clock withZone(ZoneId zone) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Instant instant() {
			return now;
		}

	}

}
