package com.bakedandbrowned.delivery.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.bakedandbrowned.common.value.Money;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A row of {@code delivery_zones} (ADR-0010). A zone cannot be active without a fee (database
 * check {@code ck_zone_active_requires_fee}, also enforced by the service with a 422). Orders
 * snapshot the zone's id, name and fee, so changing them never alters placed orders.
 */
@Entity
@Table(name = "delivery_zones")
public class DeliveryZone {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(nullable = false, length = 60)
	private String code;

	@Column(nullable = false, length = 120)
	private String name;

	@Column(name = "delivery_fee", precision = 12, scale = 2)
	private @Nullable BigDecimal deliveryFee;

	@Column(name = "min_order_amount", precision = 12, scale = 2)
	private @Nullable BigDecimal minOrderAmount;

	@Column(nullable = false)
	private boolean active;

	@Column(name = "sort_order", nullable = false)
	private int sortOrder;

	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
	private Instant updatedAt;

	protected DeliveryZone() {
	}

	public DeliveryZone(Details details) {
		apply(details);
	}

	/** Full replacement of the writable fields. */
	public void replace(Details details) {
		apply(details);
	}

	// Private, so the constructor never calls an overridable method (Hibernate proxies subclass entities).
	private void apply(Details d) {
		this.code = d.code();
		this.name = d.name();
		this.deliveryFee = d.deliveryFee() == null ? null : d.deliveryFee().amount();
		this.minOrderAmount = d.minOrderAmount() == null ? null : d.minOrderAmount().amount();
		this.active = d.active();
		this.sortOrder = d.sortOrder();
	}

	public record Details(String code, String name, @Nullable Money deliveryFee, @Nullable Money minOrderAmount,
			boolean active, int sortOrder) {
	}

	public UUID getId() {
		return id;
	}

	public String getCode() {
		return code;
	}

	public String getName() {
		return name;
	}

	public @Nullable Money getDeliveryFee() {
		return deliveryFee == null ? null : Money.of(deliveryFee);
	}

	public @Nullable Money getMinOrderAmount() {
		return minOrderAmount == null ? null : Money.of(minOrderAmount);
	}

	public boolean isActive() {
		return active;
	}

	public int getSortOrder() {
		return sortOrder;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

}
