package com.bakedandbrowned.payment.service;

import java.util.List;
import java.util.UUID;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link PaymentReconciliation} every {@code app.payments.reconciliation.interval}
 * ({@code fixedDelay}: runs never overlap within one instance; other instances are kept apart by
 * the per-payment claim).
 */
@Component
public class ReconciliationJob {

	private final PaymentReconciliation reconciliation;

	ReconciliationJob(PaymentReconciliation reconciliation) {
		this.reconciliation = reconciliation;
	}

	@Scheduled(fixedDelayString = "${app.payments.reconciliation.interval}",
			initialDelayString = "${app.payments.reconciliation.interval}")
	void run() {
		reconcileDue();
	}

	/**
	 * One pass: asks about every payment due now, batch by batch, then turns payments that aged out
	 * unresolved into cases. Must not be called inside a transaction.
	 * @return how many payments were checked
	 */
	public int reconcileDue() {
		int checked = 0;
		while (true) {
			List<UUID> batch = reconciliation.due(reconciliation.batchSize());
			int claimed = 0;
			for (UUID paymentId : batch) {
				if (reconciliation.reconcile(paymentId).isPresent()) {
					claimed++;
				}
			}
			checked += claimed;
			// Done on a short batch, or when nothing in it could be checked (claimed elsewhere, or the
			// gateway is not answering): the next run tries again after the backoff.
			if (batch.size() < reconciliation.batchSize() || claimed == 0) {
				break;
			}
		}
		reconciliation.closeOutUnresolved(reconciliation.batchSize());
		return checked;
	}

}
