package com.bakedandbrowned.catalog.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bakedandbrowned.catalog.entity.Category;

public interface CategoryRepository extends JpaRepository<Category, UUID> {

	@Query("select c from Category c order by c.sortOrder, c.name")
	List<Category> findAllOrdered();

	@Query("select c from Category c where c.active = true order by c.sortOrder, c.name")
	List<Category> findActiveOrdered();

	boolean existsByNameOrSlug(String name, String slug);

	/** Another category (not {@code id}) already uses this name or slug. */
	@Query("select count(c) > 0 from Category c where (c.name = :name or c.slug = :slug) and c.id <> :id")
	boolean nameOrSlugTakenByOther(@Param("name") String name, @Param("slug") String slug, @Param("id") UUID id);

}
