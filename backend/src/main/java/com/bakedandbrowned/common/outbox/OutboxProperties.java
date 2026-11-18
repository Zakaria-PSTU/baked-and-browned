package com.bakedandbrowned.common.outbox;

import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.outbox.*}.
 *
 * @param batchSize events claimed per dispatch run
 * @param lease how long a claimed event is reserved for its delivery; if the process dies
 * mid-delivery the event becomes due again after this (at-least-once delivery)
 * @param initialBackoff delay after the first failed attempt; doubles per attempt
 * @param maxBackoff cap on the retry delay
 */
@ConfigurationProperties("app.outbox")
public record OutboxProperties(int batchSize, Duration lease, Duration initialBackoff, Duration maxBackoff) {

	public OutboxProperties {
		Objects.requireNonNull(lease, "app.outbox.lease");
		Objects.requireNonNull(initialBackoff, "app.outbox.initial-backoff");
		Objects.requireNonNull(maxBackoff, "app.outbox.max-backoff");
		if (batchSize < 1 || lease.toSeconds() < 1 || initialBackoff.toSeconds() < 1
				|| maxBackoff.compareTo(initialBackoff) < 0) {
			throw new IllegalArgumentException("app.outbox needs batch-size >= 1, lease and initial-backoff >= 1s, "
					+ "and max-backoff >= initial-backoff");
		}
	}

	/** Delay before retrying after failed attempt number {@code attempts} (1-based). */
	public Duration backoffAfter(int attempts) {
		int doublings = Math.min(Math.max(attempts - 1, 0), 30);
		Duration delay = initialBackoff.multipliedBy(1L << doublings);
		return delay.compareTo(maxBackoff) > 0 ? maxBackoff : delay;
	}

}
