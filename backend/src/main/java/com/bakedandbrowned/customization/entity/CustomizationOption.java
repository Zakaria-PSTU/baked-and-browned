package com.bakedandbrowned.customization.entity;

import java.math.BigDecimal;
import java.util.UUID;

import com.bakedandbrowned.common.value.Money;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A row of {@code customization_options}. Inactive options are hidden from customers and rejected
 * at checkout, but stay referenced by past orders, whose snapshots never change.
 */
@Entity
@Table(name = "customization_options")
public class CustomizationOption {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "group_id", nullable = false, updatable = false)
	private CustomizationGroup group;

	@Column(nullable = false, length = 120)
	private String name;

	@Column(name = "price_delta", nullable = false, precision = 12, scale = 2)
	private BigDecimal priceDelta;

	@Column(nullable = false)
	private boolean active;

	protected CustomizationOption() {
	}

	public CustomizationOption(CustomizationGroup group, String name, Money priceDelta, boolean active) {
		this.group = group;
		apply(name, priceDelta, active);
	}

	/** Full replacement of the writable fields; the group never changes. */
	public void replace(String name, Money priceDelta, boolean active) {
		apply(name, priceDelta, active);
	}

	// Private, so the constructor never calls an overridable method (Hibernate proxies subclass entities).
	private void apply(String name, Money priceDelta, boolean active) {
		this.name = name;
		this.priceDelta = priceDelta.amount();
		this.active = active;
	}

	public UUID getId() {
		return id;
	}

	public CustomizationGroup getGroup() {
		return group;
	}

	public String getName() {
		return name;
	}

	public Money getPriceDelta() {
		return Money.of(priceDelta);
	}

	public boolean isActive() {
		return active;
	}

}
