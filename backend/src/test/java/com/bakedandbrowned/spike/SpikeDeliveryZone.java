package com.bakedandbrowned.spike;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Phase 1B spike (test sources only): NUMERIC(12,2) money and TIMESTAMPTZ instants under
 * {@code ddl-auto=validate}. Replaced by the real delivery entity in Phase 2.
 */
@Entity
@Table(name = "delivery_zones")
public class SpikeDeliveryZone {

	@Id
	private UUID id;

	@Column(nullable = false, length = 60)
	private String code;

	@Column(name = "delivery_fee", precision = 12, scale = 2)
	private BigDecimal deliveryFee;

	@Column(nullable = false)
	private boolean active;

	@Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
	private Instant updatedAt;

	protected SpikeDeliveryZone() {
	}

	public String getCode() {
		return code;
	}

	public BigDecimal getDeliveryFee() {
		return deliveryFee;
	}

	public boolean isActive() {
		return active;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

}
