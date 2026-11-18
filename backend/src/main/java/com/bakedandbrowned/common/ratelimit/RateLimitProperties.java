package com.bakedandbrowned.common.ratelimit;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code app.rate-limits.policies.<name>.{limit,window}}, for example {@code login: {limit: 10, window: 5m}}. */
@ConfigurationProperties("app.rate-limits")
public record RateLimitProperties(Map<String, Policy> policies) {

	public RateLimitProperties {
		policies = policies == null ? Map.of() : Map.copyOf(policies);
	}

	/** At most {@code limit} requests per key in each {@code window}. */
	public record Policy(int limit, Duration window) {

		public Policy {
			Objects.requireNonNull(window, "rate limit window must be configured");
			if (limit < 1 || window.isNegative() || window.isZero()) {
				throw new IllegalArgumentException("rate limit needs limit >= 1 and a positive window");
			}
		}

	}

}
