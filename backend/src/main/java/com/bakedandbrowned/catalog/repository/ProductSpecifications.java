package com.bakedandbrowned.catalog.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.domain.Specification;

import com.bakedandbrowned.catalog.entity.Category;
import com.bakedandbrowned.catalog.entity.Product;
import com.bakedandbrowned.catalog.entity.ProductStatus;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;

/** Criteria for product listings; every filter is optional. */
public final class ProductSpecifications {

	private ProductSpecifications() {
	}

	/**
	 * Public catalog: ACTIVE products in active categories, optionally in the category with
	 * {@code categorySlug} and with {@code query} contained in the name (case-insensitive, matched
	 * literally: {@code %} and {@code _} are not wildcards).
	 */
	public static Specification<Product> publicCatalog(@Nullable String categorySlug, @Nullable String query) {
		return (root, cq, cb) -> {
			Join<Product, Category> category = root.join("category");
			List<Predicate> predicates = new ArrayList<>();
			predicates.add(cb.equal(root.get("status"), ProductStatus.ACTIVE));
			predicates.add(cb.isTrue(category.get("active")));
			if (categorySlug != null) {
				predicates.add(cb.equal(category.get("slug"), categorySlug));
			}
			if (query != null) {
				predicates.add(cb.like(cb.lower(root.get("name")), containsPattern(query), '\\'));
			}
			return cb.and(predicates.toArray(Predicate[]::new));
		};
	}

	/** Admin listing: any status, optionally filtered by status and category. */
	public static Specification<Product> adminCatalog(@Nullable ProductStatus status, @Nullable UUID categoryId) {
		return (root, cq, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			if (status != null) {
				predicates.add(cb.equal(root.get("status"), status));
			}
			if (categoryId != null) {
				predicates.add(cb.equal(root.get("category").get("id"), categoryId));
			}
			return cb.and(predicates.toArray(Predicate[]::new));
		};
	}

	/** {@code %query%} in lower case, with LIKE metacharacters escaped by {@code \}. */
	static String containsPattern(String query) {
		String escaped = query.toLowerCase(Locale.ROOT)
			.replace("\\", "\\\\")
			.replace("%", "\\%")
			.replace("_", "\\_");
		return "%" + escaped + "%";
	}

}
