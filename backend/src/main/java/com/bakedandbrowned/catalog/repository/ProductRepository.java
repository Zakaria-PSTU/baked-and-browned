package com.bakedandbrowned.catalog.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bakedandbrowned.catalog.entity.Product;
import com.bakedandbrowned.catalog.entity.ProductStatus;

import jakarta.persistence.LockModeType;

public interface ProductRepository extends JpaRepository<Product, UUID>, JpaSpecificationExecutor<Product> {

	/** The publicly visible product: ACTIVE, in an active category. */
	default Optional<Product> findVisibleBySlug(String slug) {
		return findBySlugInActiveCategory(slug, ProductStatus.ACTIVE);
	}

	/**
	 * Status is a bound parameter on purpose: Hibernate renders an enum <em>literal</em> as a cast to
	 * a type named after the Java enum ({@code productstatus}), not the PostgreSQL {@code product_status}.
	 */
	@Query("""
			select p from Product p join fetch p.category c
			where p.slug = :slug and p.status = :status and c.active = true""")
	Optional<Product> findBySlugInActiveCategory(@Param("slug") String slug, @Param("status") ProductStatus status);

	@Query("select p from Product p join fetch p.category where p.id = :id")
	Optional<Product> findWithCategory(@Param("id") UUID id);

	/** Products with these ids that may be purchased: {@code status} (ACTIVE) in an active category. */
	@Query("""
			select p from Product p join fetch p.category c
			where p.id in :ids and p.status = :status and c.active = true""")
	List<Product> findPurchasable(@Param("ids") Collection<UUID> ids, @Param("status") ProductStatus status);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from Product p where p.id = :id")
	Optional<Product> lockById(@Param("id") UUID id);

	boolean existsBySlug(String slug);

	boolean existsBySlugAndIdNot(String slug, UUID id);

}
