package com.bakedandbrowned.catalog.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bakedandbrowned.audit.service.AuditLog;
import com.bakedandbrowned.catalog.dto.CatalogDtos.AdminProductResponse;
import com.bakedandbrowned.catalog.dto.CatalogDtos.ProductWrite;
import com.bakedandbrowned.catalog.entity.Category;
import com.bakedandbrowned.catalog.entity.Product;
import com.bakedandbrowned.catalog.entity.ProductStatus;
import com.bakedandbrowned.catalog.repository.CategoryRepository;
import com.bakedandbrowned.catalog.repository.ProductCustomizationReader;
import com.bakedandbrowned.catalog.repository.ProductCustomizationReader.GroupView;
import com.bakedandbrowned.catalog.repository.ProductRepository;
import com.bakedandbrowned.catalog.repository.ProductSpecifications;
import com.bakedandbrowned.common.web.ApiException;
import com.bakedandbrowned.common.web.PageResponse;

import jakarta.persistence.EntityManager;

/**
 * Admin product management. Prices set here are list prices; placed orders keep their own
 * snapshots. Every change is audited in the same transaction, with before/after values for the
 * fields that changed (free-text description only as "changed").
 */
@Service
public class ProductAdminService {

	public static final String PRODUCT_NOT_FOUND = "PRODUCT_NOT_FOUND";

	public static final String SLUG_TAKEN = "SLUG_TAKEN";

	public static final String UNKNOWN_CATEGORY = "UNKNOWN_CATEGORY";

	private final ProductRepository products;

	private final CategoryRepository categories;

	private final ProductCustomizationReader customizations;

	private final AuditLog audit;

	private final EntityManager entityManager;

	public ProductAdminService(ProductRepository products, CategoryRepository categories,
			ProductCustomizationReader customizations, AuditLog audit, EntityManager entityManager) {
		this.products = products;
		this.categories = categories;
		this.customizations = customizations;
		this.audit = audit;
		this.entityManager = entityManager;
	}

	@Transactional(readOnly = true)
	public PageResponse<AdminProductResponse> list(@Nullable ProductStatus status, @Nullable UUID categoryId, int page,
			int size) {
		Page<Product> found = products.findAll(ProductSpecifications.adminCatalog(status, categoryId),
				PageRequest.of(page, size, CatalogService.PRODUCT_ORDER));
		Map<UUID, List<GroupView>> groups = customizations
			.forProducts(found.getContent().stream().map(Product::getId).toList());
		Map<UUID, List<List<UUID>>> disallowed = customizations
			.disallowedCombinations(found.getContent().stream().map(Product::getId).toList());
		return PageResponse.of(found, p -> AdminProductResponse.from(p, groups.getOrDefault(p.getId(), List.of()),
				disallowed.getOrDefault(p.getId(), List.of())));
	}

	@Transactional(readOnly = true)
	public AdminProductResponse get(UUID id) {
		return view(find(id));
	}

	@Transactional
	public AdminProductResponse create(UUID actor, ProductWrite write) {
		if (products.existsBySlug(write.slug())) {
			throw slugTaken();
		}
		ProductStatus status = write.status() == null ? ProductStatus.DRAFT : write.status();
		Product product = save(new Product(details(write, status)));
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("slug", product.getSlug());
		details.put("status", product.getStatus().name());
		details.put("basePrice", product.getBasePrice().amount().toPlainString());
		audit.admin(actor, "PRODUCT_CREATED", "product", product.getId(), null, details);
		return view(product);
	}

	@Transactional
	public AdminProductResponse update(UUID actor, UUID id, ProductWrite write) {
		Product product = find(id);
		if (products.existsBySlugAndIdNot(write.slug(), id)) {
			throw slugTaken();
		}
		ProductStatus status = write.status() == null ? product.getStatus() : write.status();
		Product.Details next = details(write, status);
		Changes changes = new Changes().field("categoryId", product.getCategory().getId(), next.category().getId())
			.field("name", product.getName(), next.name())
			.field("slug", product.getSlug(), next.slug())
			.field("basePrice", product.getBasePrice().amount().toPlainString(), next.basePrice().amount().toPlainString())
			.field("status", product.getStatus().name(), next.status().name())
			.field("minLeadHours", product.getMinLeadHours(), next.minLeadHours())
			.field("productionUnits", product.getProductionUnits(), next.productionUnits())
			.field("cancellationCutoffHours", product.getCancellationCutoffHours(), next.cancellationCutoffHours())
			.field("imageUrl", product.getImageUrl(), next.imageUrl())
			.flag("description", product.getDescription(), next.description());
		product.replace(next);
		save(product);
		if (!changes.isEmpty()) {
			audit.admin(actor, "PRODUCT_UPDATED", "product", id, null, changes.asDetails());
		}
		return view(product);
	}

	private Product find(UUID id) {
		return products.findWithCategory(id)
			.orElseThrow(() -> ApiException.notFound(PRODUCT_NOT_FOUND, "Product not found."));
	}

	private Product.Details details(ProductWrite write, ProductStatus status) {
		Category category = categories.findById(write.categoryId())
			.orElseThrow(() -> ApiException.unprocessable(UNKNOWN_CATEGORY, "categoryId: no such category."));
		String description = write.description() == null || write.description().isBlank() ? null
				: write.description().strip();
		return new Product.Details(category, write.name().strip(), write.slug(), description, write.basePrice(),
				write.imageUrl(), status, write.minLeadHours(), write.productionUnits(),
				write.cancellationCutoffHoursOrDefault());
	}

	/** Flushes, then reloads the database-maintained timestamps. */
	private Product save(Product product) {
		try {
			Product saved = products.saveAndFlush(product);
			entityManager.refresh(saved);
			return saved;
		}
		catch (DataIntegrityViolationException ex) {
			throw slugTaken();
		}
	}

	private AdminProductResponse view(Product product) {
		return AdminProductResponse.from(product, customizations.forProduct(product.getId()),
				customizations.disallowedCombinations(product.getId()));
	}

	private static ApiException slugTaken() {
		return ApiException.conflict(SLUG_TAKEN, "Another product already uses this slug.");
	}

}
