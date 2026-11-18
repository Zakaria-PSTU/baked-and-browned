package com.bakedandbrowned.catalog.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.bakedandbrowned.catalog.entity.Category;
import com.bakedandbrowned.catalog.entity.Product;
import com.bakedandbrowned.catalog.entity.ProductStatus;
import com.bakedandbrowned.catalog.repository.ProductCustomizationReader.GroupView;
import com.bakedandbrowned.catalog.repository.ProductCustomizationReader.OptionView;
import com.bakedandbrowned.common.validation.HttpsUrl;
import com.bakedandbrowned.common.value.Money;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * API shapes of the catalog (contract tags Catalog and Admin). Public and admin views are distinct
 * types (ADR-0014): public responses never carry status, production capacity or timestamps.
 */
public final class CatalogDtos {

	/** Contract slug pattern for categories and products. */
	public static final String SLUG = "^[a-z0-9]+(-[a-z0-9]+)*$";

	private CatalogDtos() {
	}

	// ------------------------------------------------------------------ categories

	/** Contract {@code Category}. */
	public record CategoryResponse(UUID id, String name, String slug) {

		public static CategoryResponse from(Category c) {
			return new CategoryResponse(c.getId(), c.getName(), c.getSlug());
		}

	}

	/** Contract {@code AdminCategory}. */
	public record AdminCategoryResponse(UUID id, String name, String slug, int sortOrder, boolean active) {

		public static AdminCategoryResponse from(Category c) {
			return new AdminCategoryResponse(c.getId(), c.getName(), c.getSlug(), c.getSortOrder(), c.isActive());
		}

	}

	/** Contract {@code CategoryWrite}; omitted optional fields take the contract defaults. */
	public record CategoryWrite(@NotBlank @Size(min = 2, max = 100) String name,
			@NotNull @Size(max = 120) @Pattern(regexp = SLUG) String slug, @Nullable Integer sortOrder,
			@Nullable Boolean active) {

		public int sortOrderOrDefault() {
			return sortOrder == null ? 0 : sortOrder;
		}

		public boolean activeOrDefault() {
			return active == null || active;
		}

	}

	// ------------------------------------------------------------------ products

	/** Contract {@code Option}. */
	public record OptionResponse(UUID id, String name, Money priceDelta) {

		static OptionResponse from(OptionView o) {
			return new OptionResponse(o.id(), o.name(), o.priceDelta());
		}

	}

	/** Contract {@code CustomizationGroup} as offered with one product. */
	public record CustomizationGroupResponse(UUID id, String code, String name, String selectionType, boolean required,
			int minSelections, int maxSelections, @Nullable Integer textMaxLength, List<OptionResponse> options) {

		public static List<CustomizationGroupResponse> from(List<GroupView> groups) {
			return groups.stream()
				.map(g -> new CustomizationGroupResponse(g.id(), g.code(), g.name(), g.selectionType(), g.required(),
						g.minSelections(), g.maxSelections(), g.textMaxLength(),
						g.options().stream().map(OptionResponse::from).toList()))
				.toList();
		}

	}

	/** Contract {@code Product} (public). */
	public record ProductResponse(UUID id, String name, String slug, UUID categoryId, @Nullable String description,
			@Nullable String imageUrl, Money basePrice, String currency, int minLeadHours, int cancellationCutoffHours,
			List<CustomizationGroupResponse> customizationGroups) {

		public static ProductResponse from(Product p, List<GroupView> groups) {
			return new ProductResponse(p.getId(), p.getName(), p.getSlug(), p.getCategory().getId(), p.getDescription(),
					p.getImageUrl(), p.getBasePrice(), "BDT", p.getMinLeadHours(), p.getCancellationCutoffHours(),
					CustomizationGroupResponse.from(groups));
		}

	}

	/** Contract {@code AdminProduct}. */
	public record AdminProductResponse(UUID id, String name, String slug, UUID categoryId, @Nullable String description,
			@Nullable String imageUrl, Money basePrice, String currency, int minLeadHours, int cancellationCutoffHours,
			List<CustomizationGroupResponse> customizationGroups, ProductStatus status, int productionUnits,
			Instant createdAt, Instant updatedAt) {

		public static AdminProductResponse from(Product p, List<GroupView> groups) {
			return new AdminProductResponse(p.getId(), p.getName(), p.getSlug(), p.getCategory().getId(),
					p.getDescription(), p.getImageUrl(), p.getBasePrice(), "BDT", p.getMinLeadHours(),
					p.getCancellationCutoffHours(), CustomizationGroupResponse.from(groups), p.getStatus(),
					p.getProductionUnits(), p.getCreatedAt(), p.getUpdatedAt());
		}

	}

	/**
	 * Contract {@code ProductWrite}. On update every field is replaced (optional fields omitted
	 * become their default or null), except {@code status}: omitted, it keeps the current status, so a
	 * partial client cannot unpublish a product by accident. On create it defaults to DRAFT.
	 */
	public record ProductWrite(@NotNull UUID categoryId, @NotBlank @Size(max = 180) String name,
			@NotNull @Size(max = 200) @Pattern(regexp = SLUG) String slug, @Nullable @Size(max = 5000) String description,
			@NotNull Money basePrice, @NotNull @Min(0) Integer minLeadHours, @NotNull @Min(1) Integer productionUnits,
			@Nullable ProductStatus status, @Nullable @Min(0) @Max(720) Integer cancellationCutoffHours,
			@Nullable @Size(max = 2048) @HttpsUrl String imageUrl) {

		public int cancellationCutoffHoursOrDefault() {
			return cancellationCutoffHours == null ? 24 : cancellationCutoffHours;
		}

	}

}
