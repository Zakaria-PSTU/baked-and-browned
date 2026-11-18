package com.bakedandbrowned.ordering.service;

import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.orders.hold-expiry.*} (ADR-0008).
 *
 * @param batchSize overdue orders looked up per pass of the job
 * @param manualReviewWindow how long a submitted MANUAL payment reference keeps the order from
 * expiring while it waits for ADMIN verification
 * @param manualReviewSlotMargin the review also ends this long before the slot starts, whichever
 * comes first
 */
@ConfigurationProperties("app.orders.hold-expiry")
public record HoldExpiryProperties(int batchSize, Duration manualReviewWindow, Duration manualReviewSlotMargin) {

	public HoldExpiryProperties {
		Objects.requireNonNull(manualReviewWindow, "app.orders.hold-expiry.manual-review-window");
		Objects.requireNonNull(manualReviewSlotMargin, "app.orders.hold-expiry.manual-review-slot-margin");
		if (batchSize < 1 || manualReviewWindow.isNegative() || manualReviewSlotMargin.isNegative()) {
			throw new IllegalArgumentException(
					"app.orders.hold-expiry needs batch-size >= 1 and non-negative review durations");
		}
	}

}
