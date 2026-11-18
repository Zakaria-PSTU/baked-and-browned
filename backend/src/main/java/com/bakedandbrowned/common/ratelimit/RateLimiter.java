package com.bakedandbrowned.common.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import com.bakedandbrowned.common.web.RateLimitExceededException;

/**
 * In-process fixed-window limiter for the single MVP instance (architecture: login, registration,
 * refresh, checkout, manual-payment submission; the reverse proxy is a second layer). State is lost
 * on restart and not shared between instances, which is acceptable for one node.
 * <p>
 * Keys are typically the client IP ({@code request.getRemoteAddr()}, which Tomcat resolves from
 * {@code X-Forwarded-For} only for trusted internal proxies, see {@code server.forward-headers-strategy}).
 */
@Component
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimiter {

	/** Above this many tracked keys, expired windows are swept on the next request. */
	static final int SWEEP_THRESHOLD = 10_000;

	private final Clock clock;

	private final Map<String, RateLimitProperties.Policy> policies;

	private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

	public RateLimiter(Clock clock, RateLimitProperties properties) {
		this.clock = clock;
		this.policies = properties.policies();
	}

	/**
	 * Counts one request for {@code key} under {@code policy}.
	 * @throws RateLimitExceededException (429 with Retry-After) once the window's limit is used up
	 * @throws IllegalArgumentException for an unconfigured policy: a programming error, not a
	 * reason to let traffic through
	 */
	public void check(String policy, String key) {
		RateLimitProperties.Policy limits = policies.get(policy);
		if (limits == null) {
			throw new IllegalArgumentException("No rate limit policy configured: app.rate-limits.policies." + policy);
		}
		Instant now = clock.instant();
		if (windows.size() > SWEEP_THRESHOLD) {
			windows.values().removeIf(w -> w.isOver(now));
		}
		Window window = windows.compute(policy + '|' + key,
				(k, current) -> current == null || current.isOver(now) ? new Window(now.plus(limits.window()), 1)
						: current.next());
		if (window.count() > limits.limit()) {
			long millis = Duration.between(now, window.resetsAt()).toMillis();
			throw new RateLimitExceededException((millis + 999) / 1000);
		}
	}

	int trackedKeys() {
		return windows.size();
	}

	private record Window(Instant resetsAt, int count) {

		boolean isOver(Instant now) {
			return !now.isBefore(resetsAt);
		}

		Window next() {
			// Saturate instead of overflowing under a sustained flood.
			return count == Integer.MAX_VALUE ? this : new Window(resetsAt, count + 1);
		}

	}

}
