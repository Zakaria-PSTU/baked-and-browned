package com.bakedandbrowned.customization.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bakedandbrowned.customization.entity.CustomizationGroup;

public interface CustomizationGroupRepository extends JpaRepository<CustomizationGroup, UUID> {

	List<CustomizationGroup> findAllByOrderByCodeAsc();

	boolean existsByCode(String code);

	boolean existsByCodeAndIdNot(String code, UUID id);

}
