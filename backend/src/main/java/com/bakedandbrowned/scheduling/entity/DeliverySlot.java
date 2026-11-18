package com.bakedandbrowned.scheduling.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A row of {@code delivery_slots}: bounded kitchen/courier capacity units for one time window.
 * <p>
 * The entity is read and inserted through JPA, but never updated through it: {@code reserved_units}
 * changes under concurrent checkouts, so capacity and reservations change only through guarded,
 * atomic SQL updates ({@code SlotRepository#updateCapacity}, {@code SlotCapacity}).
 */
@Entity
@Table(name = "delivery_slots")
public class DeliverySlot {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "start_at", nullable = false, updatable = false)
	private Instant startAt;

	@Column(name = "end_at", nullable = false, updatable = false)
	private Instant endAt;

	@Column(name = "capacity_units", nullable = false, updatable = false)
	private int capacityUnits;

	@Column(name = "reserved_units", nullable = false, insertable = false, updatable = false)
	private int reservedUnits;

	@Column(nullable = false, updatable = false)
	private boolean active;

	protected DeliverySlot() {
	}

	public DeliverySlot(Instant startAt, Instant endAt, int capacityUnits, boolean active) {
		this.startAt = startAt;
		this.endAt = endAt;
		this.capacityUnits = capacityUnits;
		this.active = active;
	}

	public UUID getId() {
		return id;
	}

	public Instant getStartAt() {
		return startAt;
	}

	public Instant getEndAt() {
		return endAt;
	}

	public int getCapacityUnits() {
		return capacityUnits;
	}

	public int getReservedUnits() {
		return reservedUnits;
	}

	public int getRemainingUnits() {
		return Math.max(0, capacityUnits - reservedUnits);
	}

	public boolean isActive() {
		return active;
	}

}
