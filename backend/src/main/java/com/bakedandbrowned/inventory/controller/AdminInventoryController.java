package com.bakedandbrowned.inventory.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bakedandbrowned.common.web.PageResponse;
import com.bakedandbrowned.inventory.dto.InventoryDtos.IngredientCreate;
import com.bakedandbrowned.inventory.dto.InventoryDtos.IngredientResponse;
import com.bakedandbrowned.inventory.dto.InventoryDtos.InventoryAdjustment;
import com.bakedandbrowned.inventory.dto.InventoryDtos.MovementResponse;
import com.bakedandbrowned.inventory.dto.InventoryDtos.RecipeLine;
import com.bakedandbrowned.inventory.service.InventoryAdminService;
import com.bakedandbrowned.inventory.service.RecipeService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Contract tag {@code Admin}: inventory and product recipes. ADMIN only. */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminInventoryController {

	private final InventoryAdminService inventory;

	private final RecipeService recipes;

	AdminInventoryController(InventoryAdminService inventory, RecipeService recipes) {
		this.inventory = inventory;
		this.recipes = recipes;
	}

	@GetMapping("/inventory")
	List<IngredientResponse> list() {
		return inventory.all();
	}

	@PostMapping("/inventory")
	ResponseEntity<IngredientResponse> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody IngredientCreate body) {
		return ResponseEntity.status(HttpStatus.CREATED).body(inventory.create(actor(jwt), body));
	}

	@PatchMapping("/inventory/{ingredientId}")
	IngredientResponse adjust(@AuthenticationPrincipal Jwt jwt, @PathVariable("ingredientId") UUID id,
			@Valid @RequestBody InventoryAdjustment body) {
		return inventory.adjust(actor(jwt), id, body);
	}

	@GetMapping("/inventory/{ingredientId}/movements")
	PageResponse<MovementResponse> movements(@PathVariable("ingredientId") UUID id,
			@RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
			@RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size) {
		return inventory.movements(id, page, size);
	}

	@GetMapping("/products/{productId}/ingredients")
	List<RecipeLine> recipe(@PathVariable("productId") UUID productId) {
		return recipes.recipe(productId);
	}

	@PutMapping("/products/{productId}/ingredients")
	List<RecipeLine> setRecipe(@AuthenticationPrincipal Jwt jwt, @PathVariable("productId") UUID productId,
			@RequestBody List<@Valid RecipeLine> body) {
		return recipes.replace(actor(jwt), productId, body);
	}

	private static UUID actor(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}

}
