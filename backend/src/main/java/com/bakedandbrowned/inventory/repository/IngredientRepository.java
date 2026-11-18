package com.bakedandbrowned.inventory.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bakedandbrowned.inventory.entity.Ingredient;

public interface IngredientRepository extends JpaRepository<Ingredient, UUID> {

	List<Ingredient> findAllByOrderByNameAsc();

	boolean existsByName(String name);

}
