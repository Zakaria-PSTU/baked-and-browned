package com.bakedandbrowned.catalog.controller;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bakedandbrowned.catalog.dto.CatalogDtos.AdminCategoryResponse;
import com.bakedandbrowned.catalog.dto.CatalogDtos.AdminProductResponse;
import com.bakedandbrowned.catalog.dto.CatalogDtos.CategoryWrite;
import com.bakedandbrowned.catalog.dto.CatalogDtos.ProductWrite;
import com.bakedandbrowned.catalog.entity.ProductStatus;
import com.bakedandbrowned.catalog.service.CategoryAdminService;
import com.bakedandbrowned.catalog.service.ProductAdminService;
import com.bakedandbrowned.common.web.PageResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Contract tag {@code Admin}: categories and products. ADMIN is required both by the URL rule
 * ({@code /api/v1/admin/**}) and here at method level.
 */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminCatalogController {

	private final CategoryAdminService categories;

	private final ProductAdminService products;

	AdminCatalogController(CategoryAdminService categories, ProductAdminService products) {
		this.categories = categories;
		this.products = products;
	}

	@GetMapping("/categories")
	List<AdminCategoryResponse> listCategories() {
		return categories.all();
	}

	@PostMapping("/categories")
	ResponseEntity<AdminCategoryResponse> createCategory(@AuthenticationPrincipal Jwt admin,
			@Valid @RequestBody CategoryWrite body) {
		return ResponseEntity.status(HttpStatus.CREATED).body(categories.create(actor(admin), body));
	}

	@PatchMapping("/categories/{categoryId}")
	AdminCategoryResponse updateCategory(@AuthenticationPrincipal Jwt admin, @PathVariable("categoryId") UUID id,
			@Valid @RequestBody CategoryWrite body) {
		return categories.update(actor(admin), id, body);
	}

	@GetMapping("/products")
	PageResponse<AdminProductResponse> listProducts(
			@RequestParam(name = "status", required = false) @Nullable ProductStatus status,
			@RequestParam(name = "categoryId", required = false) @Nullable UUID categoryId,
			@RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
			@RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size) {
		return products.list(status, categoryId, page, size);
	}

	@PostMapping("/products")
	ResponseEntity<AdminProductResponse> createProduct(@AuthenticationPrincipal Jwt admin,
			@Valid @RequestBody ProductWrite body) {
		return ResponseEntity.status(HttpStatus.CREATED).body(products.create(actor(admin), body));
	}

	@GetMapping("/products/{productId}")
	AdminProductResponse getProduct(@PathVariable("productId") UUID id) {
		return products.get(id);
	}

	@PatchMapping("/products/{productId}")
	AdminProductResponse updateProduct(@AuthenticationPrincipal Jwt admin, @PathVariable("productId") UUID id,
			@Valid @RequestBody ProductWrite body) {
		return products.update(actor(admin), id, body);
	}

	private static UUID actor(Jwt admin) {
		return UUID.fromString(admin.getSubject());
	}

}
