package com.bakedandbrowned.common.outbox;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls the outbox; {@code fixedDelay}, so runs never overlap within one instance. */
@Component
class OutboxDispatchJob {

	private final OutboxDispatcher dispatcher;

	OutboxDispatchJob(OutboxDispatcher dispatcher) {
		this.dispatcher = dispatcher;
	}

	@Scheduled(fixedDelayString = "${app.outbox.poll-interval}", initialDelayString = "${app.outbox.poll-interval}")
	void dispatch() {
		dispatcher.dispatchDue();
	}

}
