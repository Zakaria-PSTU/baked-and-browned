package com.bakedandbrowned.catalog.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.bakedandbrowned.catalog.dto.CatalogDtos.ProductResponse;
import com.bakedandbrowned.catalog.entity.Product;
import com.bakedandbrowned.catalog.repository.ProductCustomizationReader;
import com.bakedandbrowned.catalog.repository.ProductRepository;
import com.bakedandbrowned.common.web.ApiException;

/**
 * The catalog module's interface for other modules (architecture: cross-module calls go through
 * services, never another module's repositories).
 */
@Service
public class ProductCatalog {

	private final ProductRepository products;

	private final ProductCustomizationReader customizations;

	public ProductCatalog(ProductRepository products, ProductCustomizationReader customizations) {
		this.products = products;
		this.customizations = customizations;
	}

	/**
	 * Locks the product row ({@code SELECT ... FOR UPDATE}) for the caller's transaction, so changes
	 * that hang off a product (for example its customization assignments) serialize.
	 * @throws ApiException 404 {@code PRODUCT_NOT_FOUND}
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void lockForUpdate(UUID productId) {
		if (products.lockById(productId).isEmpty()) {
			throw ApiException.notFound(ProductAdminService.PRODUCT_NOT_FOUND, "Product not found.");
		}
	}

	/** The product in the contract's {@code Product} shape, whatever its status. */
	@Transactional(readOnly = true)
	public ProductResponse view(UUID productId) {
		Product product = products.findWithCategory(productId)
			.orElseThrow(() -> ApiException.notFound(ProductAdminService.PRODUCT_NOT_FOUND, "Product not found."));
		return ProductResponse.from(product, customizations.forProduct(productId));
	}

}
