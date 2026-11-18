package com.bakedandbrowned.ordering.service;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link HoldExpiry} every {@code app.orders.hold-expiry.interval} ({@code fixedDelay}, so runs
 * never overlap within one instance; other instances are handled by row claiming).
 */
@Component
public class HoldExpiryJob {

	private static final Logger log = LoggerFactory.getLogger(HoldExpiryJob.class);

	private final HoldExpiry expiry;

	HoldExpiryJob(HoldExpiry expiry) {
		this.expiry = expiry;
	}

	@Scheduled(fixedDelayString = "${app.orders.hold-expiry.interval}",
			initialDelayString = "${app.orders.hold-expiry.interval}")
	void run() {
		expireDue();
	}

	/**
	 * One pass: expires every order due now, batch by batch, each in its own transaction. Must not be
	 * called inside a transaction.
	 * @return how many orders this pass expired
	 */
	public int expireDue() {
		int expired = 0;
		while (true) {
			List<UUID> batch = expiry.due(expiry.batchSize());
			int claimed = 0;
			for (UUID orderId : batch) {
				if (expiry.expire(orderId)) {
					claimed++;
				}
			}
			expired += claimed;
			// Done on a short batch, or when another run holds everything left.
			if (batch.size() < expiry.batchSize() || claimed == 0) {
				break;
			}
		}
		if (expired > 0) {
			log.info("Expired {} unpaid order(s) and released their holds", expired);
		}
		return expired;
	}

}
