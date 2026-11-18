package com.bakedandbrowned.common.outbox;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

/**
 * Records a side effect to perform after commit (ADR-003). Must be called inside the business
 * transaction ({@code MANDATORY}), so the event is committed if and only if the business change is.
 * Nothing is sent here; the {@link OutboxDispatcher} delivers it later.
 */
@Component
public class OutboxPublisher {

	private final JdbcTemplate jdbc;

	private final JsonMapper json;

	public OutboxPublisher(JdbcTemplate jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	/**
	 * @param payload serialized to JSON; keep it minimal (ids, not copies of personal data) so
	 * handlers load current details themselves
	 * @return the event id
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public UUID publish(String aggregateType, UUID aggregateId, String eventType, Object payload) {
		return jdbc.queryForObject("""
				INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload)
				VALUES (?, ?, ?, ?::jsonb)
				RETURNING id""", UUID.class, aggregateType, aggregateId, eventType, json.writeValueAsString(payload));
	}

}
