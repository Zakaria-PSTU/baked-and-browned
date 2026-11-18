package com.bakedandbrowned.catalog.service;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.bakedandbrowned.catalog.dto.CatalogDtos.ProductResponse;
import com.bakedandbrowned.catalog.entity.Product;
import com.bakedandbrowned.catalog.entity.ProductStatus;
import com.bakedandbrowned.common.value.Money;
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

	/** What ordering needs to price, schedule and snapshot a product, as it is now. */
	public record PurchasableProduct(UUID id, String name, Money basePrice, int minLeadHours, int productionUnits,
			int cancellationCutoffHours) {
	}

	/**
	 * The purchasable products (ACTIVE, in an active category) among {@code ids}; absent ids are
	 * unknown or not for sale.
	 */
	@Transactional(readOnly = true)
	public Map<UUID, PurchasableProduct> purchasable(Collection<UUID> ids) {
		if (ids.isEmpty()) {
			return Map.of();
		}
		return products.findPurchasable(ids, ProductStatus.ACTIVE)
			.stream()
			.collect(Collectors.toMap(Product::getId, p -> new PurchasableProduct(p.getId(), p.getName(),
					p.getBasePrice(), p.getMinLeadHours(), p.getProductionUnits(), p.getCancellationCutoffHours())));
	}

	/** @throws ApiException 404 {@code PRODUCT_NOT_FOUND} */
	@Transactional(readOnly = true)
	public void requireExists(UUID productId) {
		if (!products.existsById(productId)) {
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
