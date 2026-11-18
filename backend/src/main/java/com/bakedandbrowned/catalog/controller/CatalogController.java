package com.bakedandbrowned.catalog.controller;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bakedandbrowned.catalog.dto.CatalogDtos.CategoryResponse;
import com.bakedandbrowned.catalog.dto.CatalogDtos.ProductResponse;
import com.bakedandbrowned.catalog.service.CatalogService;
import com.bakedandbrowned.common.web.PageResponse;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/** Contract tag {@code Catalog}: public, unauthenticated reads. */
@RestController
@RequestMapping("/api/v1")
public class CatalogController {

	private final CatalogService catalog;

	CatalogController(CatalogService catalog) {
		this.catalog = catalog;
	}

	@GetMapping("/categories")
	List<CategoryResponse> categories() {
		return catalog.activeCategories();
	}

	@GetMapping("/products")
	PageResponse<ProductResponse> products(@RequestParam(name = "category", required = false) @Nullable String category,
			@RequestParam(name = "q", required = false) @Nullable @Size(max = 100) String q,
			@RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
			@RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size) {
		return catalog.products(category, q, page, size);
	}

	@GetMapping("/products/{slug}")
	ProductResponse product(@PathVariable("slug") String slug) {
		return catalog.product(slug);
	}

}
