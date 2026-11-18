package com.bakedandbrowned.catalog.service;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bakedandbrowned.catalog.dto.CatalogDtos.CategoryResponse;
import com.bakedandbrowned.catalog.dto.CatalogDtos.ProductResponse;
import com.bakedandbrowned.catalog.entity.Product;
import com.bakedandbrowned.catalog.repository.CategoryRepository;
import com.bakedandbrowned.catalog.repository.ProductCustomizationReader;
import com.bakedandbrowned.catalog.repository.ProductCustomizationReader.GroupView;
import com.bakedandbrowned.catalog.repository.ProductRepository;
import com.bakedandbrowned.catalog.repository.ProductSpecifications;
import com.bakedandbrowned.common.web.ApiException;
import com.bakedandbrowned.common.web.PageResponse;

/**
 * The public catalog: active categories, and ACTIVE products in active categories with the
 * customizations they offer. Anything else is invisible (404 by slug, never "exists but hidden").
 */
@Service
@Transactional(readOnly = true)
public class CatalogService {

	public static final String PRODUCT_NOT_FOUND = "PRODUCT_NOT_FOUND";

	/** Stable order so paging never skips or repeats products. */
	static final Sort PRODUCT_ORDER = Sort.by("name").and(Sort.by("id"));

	private final CategoryRepository categories;

	private final ProductRepository products;

	private final ProductCustomizationReader customizations;

	public CatalogService(CategoryRepository categories, ProductRepository products,
			ProductCustomizationReader customizations) {
		this.categories = categories;
		this.products = products;
		this.customizations = customizations;
	}

	public List<CategoryResponse> activeCategories() {
		return categories.findActiveOrdered().stream().map(CategoryResponse::from).toList();
	}

	/** An unknown category slug simply matches nothing. */
	public PageResponse<ProductResponse> products(@Nullable String categorySlug, @Nullable String query, int page,
			int size) {
		Page<Product> found = products.findAll(
				ProductSpecifications.publicCatalog(blankToNull(categorySlug), blankToNull(query)),
				PageRequest.of(page, size, PRODUCT_ORDER));
		Map<java.util.UUID, List<GroupView>> groups = customizations
			.forProducts(found.getContent().stream().map(Product::getId).toList());
		return PageResponse.of(found, p -> ProductResponse.from(p, groups.getOrDefault(p.getId(), List.of())));
	}

	public ProductResponse product(String slug) {
		Product product = products.findVisibleBySlug(slug)
			.orElseThrow(() -> ApiException.notFound(PRODUCT_NOT_FOUND, "Product not found."));
		return ProductResponse.from(product, customizations.forProduct(product.getId()));
	}

	private static @Nullable String blankToNull(@Nullable String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

}
