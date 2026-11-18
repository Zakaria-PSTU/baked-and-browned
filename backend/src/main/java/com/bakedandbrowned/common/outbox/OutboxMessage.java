package com.bakedandbrowned.common.outbox;

import java.util.UUID;

/**
 * One claimed outbox event, as handed to an {@link OutboxHandler}.
 *
 * @param id unique per event: handlers use it as their idempotency key
 * @param payload the JSON payload as written by {@link OutboxPublisher}
 * @param attempt 1 for the first delivery, then 2, 3, ...
 */
public record OutboxMessage(UUID id, String aggregateType, UUID aggregateId, String eventType, String payload,
		int attempt) {

	/** Payloads can contain personal data: never print them. */
	@Override
	public String toString() {
		return "OutboxMessage[id=" + id + ", eventType=" + eventType + ", aggregate=" + aggregateType + "/" + aggregateId
				+ ", attempt=" + attempt + "]";
	}

}
