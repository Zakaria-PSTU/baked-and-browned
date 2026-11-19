package com.bakedandbrowned.common.outbox;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls the outbox; {@code fixedDelay}, so runs never overlap within one instance. */
// Not in the provision-admin command (ADR-0024): it sends no notices, so it needs no SmsSender.
@Profile("!provision-admin")
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
