package com.bakedandbrowned.common.outbox;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Delivers due outbox events (architecture, Scheduled workers):
 * <ol>
 * <li><b>Claim</b> in one autocommitted statement: due, unprocessed events are locked with
 * {@code FOR UPDATE SKIP LOCKED} (concurrent dispatchers never claim the same row), their attempt
 * count is incremented and {@code next_attempt_at} is pushed out by the lease.</li>
 * <li><b>Deliver</b> each to its {@link OutboxHandler} with no transaction or lock held.</li>
 * <li><b>Record</b>: success sets {@code processed_at}; failure stores the error and reschedules with
 * exponential backoff.</li>
 * </ol>
 * A crash between claim and record leaves the event to be claimed again once the lease expires.
 */
@Component
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxDispatcher {

	static final int MAX_ERROR_LENGTH = 1000;

	private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

	private final JdbcTemplate jdbc;

	private final OutboxProperties properties;

	private final Map<String, OutboxHandler> handlers;

	public OutboxDispatcher(JdbcTemplate jdbc, OutboxProperties properties, List<OutboxHandler> handlers) {
		this.jdbc = jdbc;
		this.properties = properties;
		// toMap rejects two handlers for one event type at startup.
		this.handlers = handlers.stream().collect(Collectors.toUnmodifiableMap(OutboxHandler::eventType, Function.identity()));
	}

	/** Claims and delivers one batch; returns how many events were delivered successfully. */
	public int dispatchDue() {
		List<OutboxMessage> claimed = claim();
		int delivered = 0;
		for (OutboxMessage message : claimed) {
			if (deliver(message)) {
				delivered++;
			}
		}
		return delivered;
	}

	/** Unprocessed events, for backlog monitoring. */
	public long pendingCount() {
		Long count = jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE processed_at IS NULL", Long.class);
		return count == null ? 0 : count;
	}

	private List<OutboxMessage> claim() {
		// UPDATE ... RETURNING has no defined row order: deliver the batch in creation order, so an
		// aggregate's events (e.g. "received" before "on its way") arrive in the order they happened.
		return jdbc.query("""
				WITH claimed AS (
				  UPDATE outbox_events
				  SET attempts = attempts + 1, next_attempt_at = now() + make_interval(secs => ?)
				  WHERE id IN (
				      SELECT id FROM outbox_events
				      WHERE processed_at IS NULL AND next_attempt_at <= now()
				      ORDER BY next_attempt_at, id
				      LIMIT ?
				      FOR UPDATE SKIP LOCKED)
				  RETURNING id, aggregate_type, aggregate_id, event_type, payload::text AS payload, attempts, created_at)
				SELECT id, aggregate_type, aggregate_id, event_type, payload, attempts FROM claimed
				ORDER BY created_at, id""",
				(rs, row) -> new OutboxMessage(rs.getObject("id", UUID.class), rs.getString("aggregate_type"),
						rs.getObject("aggregate_id", UUID.class), rs.getString("event_type"), rs.getString("payload"),
						rs.getInt("attempts")),
				properties.lease().toSeconds(), properties.batchSize());
	}

	private boolean deliver(OutboxMessage message) {
		OutboxHandler handler = handlers.get(message.eventType());
		try {
			if (handler == null) {
				throw new IllegalStateException("No OutboxHandler for event type " + message.eventType());
			}
			handler.handle(message);
		}
		catch (Exception ex) {
			long backoff = properties.backoffAfter(message.attempt()).toSeconds();
			// "attempts = ?": if our lease expired and another run re-claimed the event, leave its schedule alone.
			jdbc.update("""
					UPDATE outbox_events SET last_error = ?, next_attempt_at = now() + make_interval(secs => ?)
					WHERE id = ? AND processed_at IS NULL AND attempts = ?""", describe(ex), backoff, message.id(),
					message.attempt());
			// The message toString carries no payload; the exception text may, so it is not logged here.
			log.warn("Outbox delivery failed for {}: {}; retrying in {}s", message, ex.getClass().getName(), backoff);
			return false;
		}
		jdbc.update("UPDATE outbox_events SET processed_at = now(), last_error = NULL WHERE id = ? AND processed_at IS NULL",
				message.id());
		return true;
	}

	private static String describe(Exception ex) {
		String text = ex.getClass().getName() + (ex.getMessage() == null ? "" : ": " + ex.getMessage());
		return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH);
	}

}
