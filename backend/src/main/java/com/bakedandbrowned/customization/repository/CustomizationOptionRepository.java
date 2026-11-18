package com.bakedandbrowned.customization.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bakedandbrowned.customization.entity.CustomizationOption;

public interface CustomizationOptionRepository extends JpaRepository<CustomizationOption, UUID> {

	@Query("select o from CustomizationOption o where o.group.id in :groupIds order by o.name, o.id")
	List<CustomizationOption> findByGroupIds(@Param("groupIds") Collection<UUID> groupIds);

	boolean existsByGroupId(UUID groupId);

	boolean existsByGroupIdAndName(UUID groupId, String name);

	boolean existsByGroupIdAndNameAndIdNot(UUID groupId, String name, UUID id);

	@Query("select o from CustomizationOption o join fetch o.group where o.id = :id")
	java.util.Optional<CustomizationOption> findWithGroup(@Param("id") UUID id);

}
