package com.bakedandbrowned.scheduling.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Slot capacity reservations for checkout (business rules, Delivery scheduling). Each operation is
 * one guarded {@code UPDATE}: PostgreSQL row-locks the slot for the statement and re-checks the
 * condition, so two customers racing for the last units can never both succeed, and the database
 * check {@code reserved_units <= capacity_units} backs it up. Callers run inside their checkout or
 * release transaction ({@code MANDATORY}) and record the matching {@code slot_reservations} row.
 */
@Component
public class SlotCapacity {

	private final JdbcTemplate jdbc;

	public SlotCapacity(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Reserves {@code units} on an active slot that has not started by {@code now}.
	 * @return false if the slot is unknown, closed, started, or has fewer units left
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean tryReserve(UUID slotId, int units, Instant now) {
		if (units < 1) {
			throw new IllegalArgumentException("units must be positive");
		}
		return jdbc.update("""
				UPDATE delivery_slots SET reserved_units = reserved_units + ?
				WHERE id = ? AND active AND start_at > ? AND reserved_units + ? <= capacity_units""", units, slotId,
				java.sql.Timestamp.from(now), units) == 1;
	}

	/**
	 * Returns {@code units} previously reserved (cancellation, expiry). Works on closed and past
	 * slots too. Callers guarantee at-most-once release via the reservation row's status.
	 * @throws IllegalStateException if more would be released than is reserved (a bookkeeping bug)
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void release(UUID slotId, int units) {
		int updated = jdbc.update("""
				UPDATE delivery_slots SET reserved_units = reserved_units - ?
				WHERE id = ? AND reserved_units >= ?""", units, slotId, units);
		if (updated != 1) {
			throw new IllegalStateException("Cannot release " + units + " units from slot " + slotId);
		}
	}

}
