package com.bakedandbrowned.catalog.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

import com.bakedandbrowned.common.value.Money;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A row of {@code products}. The price here is the current list price; orders keep their own
 * immutable snapshot, so changing it never affects placed orders. {@code created_at}/{@code updated_at}
 * are maintained by the database (default and trigger).
 */
@Entity
@Table(name = "products")
public class Product {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "category_id", nullable = false)
	private Category category;

	@Column(nullable = false, length = 180)
	private String name;

	@Column(nullable = false, length = 200)
	private String slug;

	@Column(columnDefinition = "text")
	private @Nullable String description;

	@Column(name = "base_price", nullable = false, precision = 12, scale = 2)
	private BigDecimal basePrice;

	@Column(name = "image_url", columnDefinition = "text")
	private @Nullable String imageUrl;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(nullable = false)
	private ProductStatus status;

	@Column(name = "min_lead_hours", nullable = false)
	private int minLeadHours;

	@Column(name = "production_units", nullable = false)
	private int productionUnits;

	@Column(name = "cancellation_cutoff_hours", nullable = false)
	private int cancellationCutoffHours;

	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
	private Instant updatedAt;

	protected Product() {
	}

	public Product(Details details) {
		apply(details);
	}

	/** Full replacement of the writable fields. */
	public void replace(Details details) {
		apply(details);
	}

	// Private, so the constructor never calls an overridable method (Hibernate proxies subclass entities).
	private void apply(Details details) {
		this.category = details.category();
		this.name = details.name();
		this.slug = details.slug();
		this.description = details.description();
		this.basePrice = details.basePrice().amount();
		this.imageUrl = details.imageUrl();
		this.status = details.status();
		this.minLeadHours = details.minLeadHours();
		this.productionUnits = details.productionUnits();
		this.cancellationCutoffHours = details.cancellationCutoffHours();
	}

	/** Everything an admin can set, already validated. */
	public record Details(Category category, String name, String slug, @Nullable String description, Money basePrice,
			@Nullable String imageUrl, ProductStatus status, int minLeadHours, int productionUnits,
			int cancellationCutoffHours) {
	}

	public UUID getId() {
		return id;
	}

	public Category getCategory() {
		return category;
	}

	public String getName() {
		return name;
	}

	public String getSlug() {
		return slug;
	}

	public @Nullable String getDescription() {
		return description;
	}

	public Money getBasePrice() {
		return Money.of(basePrice);
	}

	public @Nullable String getImageUrl() {
		return imageUrl;
	}

	public ProductStatus getStatus() {
		return status;
	}

	public int getMinLeadHours() {
		return minLeadHours;
	}

	public int getProductionUnits() {
		return productionUnits;
	}

	public int getCancellationCutoffHours() {
		return cancellationCutoffHours;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

}
