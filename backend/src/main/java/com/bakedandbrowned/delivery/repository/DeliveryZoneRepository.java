package com.bakedandbrowned.delivery.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bakedandbrowned.delivery.entity.DeliveryZone;

public interface DeliveryZoneRepository extends JpaRepository<DeliveryZone, UUID> {

	@Query("select z from DeliveryZone z order by z.sortOrder, z.name")
	List<DeliveryZone> findAllOrdered();

	/** Zones a customer may select: active and with a fee. */
	@Query("select z from DeliveryZone z where z.active = true and z.deliveryFee is not null order by z.sortOrder, z.name")
	List<DeliveryZone> findSelectable();

	Optional<DeliveryZone> findByCode(String code);

	boolean existsByCodeOrName(String code, String name);

	@Query("select count(z) > 0 from DeliveryZone z where (z.code = :code or z.name = :name) and z.id <> :id")
	boolean codeOrNameTakenByOther(@Param("code") String code, @Param("name") String name, @Param("id") UUID id);

}
