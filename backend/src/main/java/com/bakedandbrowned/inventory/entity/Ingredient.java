package com.bakedandbrowned.inventory.entity;

import java.math.BigDecimal;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.bakedandbrowned.common.value.Quantity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A row of {@code ingredients}. Read and inserted through JPA; {@code on_hand} and {@code reserved}
 * change only through guarded, locked SQL ({@code InventoryLedger}, {@code StockReservations}), so
 * JPA never writes them back over a concurrent reservation.
 */
@Entity
@Table(name = "ingredients")
public class Ingredient {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(nullable = false, length = 120, updatable = false)
	private String name;

	@Column(nullable = false, length = 20, updatable = false)
	private String unit;

	@Column(name = "on_hand", nullable = false, precision = 14, scale = 3, updatable = false)
	private BigDecimal onHand;

	@Column(nullable = false, precision = 14, scale = 3, insertable = false, updatable = false)
	private BigDecimal reserved;

	@Column(name = "low_stock_threshold", precision = 14, scale = 3, updatable = false)
	private @Nullable BigDecimal lowStockThreshold;

	protected Ingredient() {
	}

	public Ingredient(String name, String unit, Quantity onHand, @Nullable Quantity lowStockThreshold) {
		this.name = name;
		this.unit = unit;
		this.onHand = onHand.value();
		this.lowStockThreshold = lowStockThreshold == null ? null : lowStockThreshold.value();
	}

	public UUID getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getUnit() {
		return unit;
	}

	public Quantity getOnHand() {
		return Quantity.of(onHand);
	}

	/** Zero right after insert (database default) until the entity is reloaded. */
	public Quantity getReserved() {
		return reserved == null ? Quantity.ZERO : Quantity.of(reserved);
	}

	public Quantity getAvailable() {
		return getOnHand().minus(getReserved());
	}

	public @Nullable Quantity getLowStockThreshold() {
		return lowStockThreshold == null ? null : Quantity.of(lowStockThreshold);
	}

}
