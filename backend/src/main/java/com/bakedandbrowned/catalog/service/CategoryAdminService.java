package com.bakedandbrowned.catalog.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bakedandbrowned.audit.service.AuditLog;
import com.bakedandbrowned.catalog.dto.CatalogDtos.AdminCategoryResponse;
import com.bakedandbrowned.catalog.dto.CatalogDtos.CategoryWrite;
import com.bakedandbrowned.catalog.entity.Category;
import com.bakedandbrowned.catalog.repository.CategoryRepository;
import com.bakedandbrowned.common.web.ApiException;

/** Admin category management; every change is audited in the same transaction. */
@Service
public class CategoryAdminService {

	public static final String CATEGORY_EXISTS = "CATEGORY_EXISTS";

	public static final String CATEGORY_NOT_FOUND = "CATEGORY_NOT_FOUND";

	private final CategoryRepository categories;

	private final AuditLog audit;

	public CategoryAdminService(CategoryRepository categories, AuditLog audit) {
		this.categories = categories;
		this.audit = audit;
	}

	@Transactional(readOnly = true)
	public List<AdminCategoryResponse> all() {
		return categories.findAllOrdered().stream().map(AdminCategoryResponse::from).toList();
	}

	@Transactional
	public AdminCategoryResponse create(UUID actor, CategoryWrite write) {
		String name = write.name().strip();
		if (categories.existsByNameOrSlug(name, write.slug())) {
			throw exists();
		}
		Category category = save(new Category(name, write.slug(), write.sortOrderOrDefault(), write.activeOrDefault()));
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("name", category.getName());
		details.put("slug", category.getSlug());
		details.put("sortOrder", category.getSortOrder());
		details.put("active", category.isActive());
		audit.admin(actor, "CATEGORY_CREATED", "category", category.getId(), null, details);
		return AdminCategoryResponse.from(category);
	}

	@Transactional
	public AdminCategoryResponse update(UUID actor, UUID id, CategoryWrite write) {
		Category category = categories.findById(id)
			.orElseThrow(() -> ApiException.notFound(CATEGORY_NOT_FOUND, "Category not found."));
		String name = write.name().strip();
		if (categories.nameOrSlugTakenByOther(name, write.slug(), id)) {
			throw exists();
		}
		Changes changes = new Changes().field("name", category.getName(), name)
			.field("slug", category.getSlug(), write.slug())
			.field("sortOrder", category.getSortOrder(), write.sortOrderOrDefault())
			.field("active", category.isActive(), write.activeOrDefault());
		category.replace(name, write.slug(), write.sortOrderOrDefault(), write.activeOrDefault());
		save(category);
		if (!changes.isEmpty()) {
			audit.admin(actor, "CATEGORY_UPDATED", "category", id, null, changes.asDetails());
		}
		return AdminCategoryResponse.from(category);
	}

	private Category save(Category category) {
		try {
			return categories.saveAndFlush(category);
		}
		catch (DataIntegrityViolationException ex) {
			// A concurrent change won the unique name/slug index.
			throw exists();
		}
	}

	private static ApiException exists() {
		return ApiException.conflict(CATEGORY_EXISTS, "Another category already uses this name or slug.");
	}

}
