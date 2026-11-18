package com.bakedandbrowned.payment.service;

import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.payments.reconciliation.*} (architecture, Scheduled workers).
 *
 * @param batchSize payments asked about per batch
 * @param minAge a session is left to its provider callback this long before the worker asks
 * @param recheck the shortest wait between two asks about one payment; it grows to a quarter of
 * the payment's age, so abandoned checkouts are asked about rarely
 * @param lookback how long after creation a payment is still asked about; after that, one whose
 * last ask failed becomes an AMBIGUOUS_STATUS case
 */
@ConfigurationProperties("app.payments.reconciliation")
public record ReconciliationProperties(int batchSize, Duration minAge, Duration recheck, Duration lookback) {

	public ReconciliationProperties {
		Objects.requireNonNull(minAge, "app.payments.reconciliation.min-age");
		Objects.requireNonNull(recheck, "app.payments.reconciliation.recheck");
		Objects.requireNonNull(lookback, "app.payments.reconciliation.lookback");
		if (batchSize < 1 || minAge.isNegative() || recheck.isNegative() || recheck.isZero()
				|| lookback.compareTo(minAge) <= 0) {
			throw new IllegalArgumentException(
					"app.payments.reconciliation needs batch-size >= 1, a positive recheck and lookback > min-age");
		}
	}

}
