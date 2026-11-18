package com.bakedandbrowned.common.outbox;

/**
 * Delivers one event type (for example sending an "order confirmed" notice). Delivery is
 * at-least-once, so handlers must be idempotent, keyed by {@link OutboxMessage#id()}.
 * <p>
 * Handlers run outside any database transaction and may call external services. Throwing marks
 * the attempt failed; the event is retried with exponential backoff. A failure never affects the
 * business transaction that published the event.
 */
public interface OutboxHandler {

	/** The {@code event_type} this handler delivers; exactly one handler per type. */
	String eventType();

	void handle(OutboxMessage message) throws Exception;

}
