package com.bakedandbrowned.customization.controller;

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
import org.springframework.web.bind.annotation.RestController;

import com.bakedandbrowned.catalog.dto.CatalogDtos.ProductResponse;
import com.bakedandbrowned.catalog.service.ProductCatalog;
import com.bakedandbrowned.customization.dto.CustomizationDtos.AdminGroupResponse;
import com.bakedandbrowned.customization.dto.CustomizationDtos.AdminOptionResponse;
import com.bakedandbrowned.customization.dto.CustomizationDtos.AssignmentWrite;
import com.bakedandbrowned.customization.dto.CustomizationDtos.DisallowedCombination;
import com.bakedandbrowned.customization.dto.CustomizationDtos.GroupWrite;
import com.bakedandbrowned.customization.dto.CustomizationDtos.OptionWrite;
import com.bakedandbrowned.customization.service.CustomizationAdminService;
import com.bakedandbrowned.customization.service.ProductCustomizationService;

import jakarta.validation.Valid;

/**
 * Contract tag {@code Admin}: customization groups, options, product assignments and disallowed
 * combinations. ADMIN only.
 */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminCustomizationController {

	private final CustomizationAdminService admin;

	private final ProductCustomizationService assignments;

	private final ProductCatalog catalog;

	AdminCustomizationController(CustomizationAdminService admin, ProductCustomizationService assignments,
			ProductCatalog catalog) {
		this.admin = admin;
		this.assignments = assignments;
		this.catalog = catalog;
	}

	@GetMapping("/customization-groups")
	List<AdminGroupResponse> listGroups() {
		return admin.all();
	}

	@PostMapping("/customization-groups")
	ResponseEntity<AdminGroupResponse> createGroup(@AuthenticationPrincipal Jwt jwt,
			@Valid @RequestBody GroupWrite body) {
		return ResponseEntity.status(HttpStatus.CREATED).body(admin.createGroup(actor(jwt), body));
	}

	@PatchMapping("/customization-groups/{groupId}")
	AdminGroupResponse updateGroup(@AuthenticationPrincipal Jwt jwt, @PathVariable("groupId") UUID id,
			@Valid @RequestBody GroupWrite body) {
		return admin.updateGroup(actor(jwt), id, body);
	}

	@PostMapping("/customization-groups/{groupId}/options")
	ResponseEntity<AdminOptionResponse> createOption(@AuthenticationPrincipal Jwt jwt,
			@PathVariable("groupId") UUID groupId, @Valid @RequestBody OptionWrite body) {
		return ResponseEntity.status(HttpStatus.CREATED).body(admin.createOption(actor(jwt), groupId, body));
	}

	@PatchMapping("/customization-options/{optionId}")
	AdminOptionResponse updateOption(@AuthenticationPrincipal Jwt jwt, @PathVariable("optionId") UUID id,
			@Valid @RequestBody OptionWrite body) {
		return admin.updateOption(actor(jwt), id, body);
	}

	@PutMapping("/products/{productId}/customizations")
	ProductResponse setProductCustomizations(@AuthenticationPrincipal Jwt jwt, @PathVariable("productId") UUID id,
			@RequestBody List<@Valid AssignmentWrite> body) {
		assignments.replaceAssignments(actor(jwt), id, body);
		return catalog.view(id);
	}

	@GetMapping("/products/{productId}/disallowed-combinations")
	List<DisallowedCombination> disallowedCombinations(@PathVariable("productId") UUID id) {
		return assignments.disallowedCombinations(id);
	}

	@PutMapping("/products/{productId}/disallowed-combinations")
	List<DisallowedCombination> setDisallowedCombinations(@AuthenticationPrincipal Jwt jwt,
			@PathVariable("productId") UUID id, @RequestBody List<@Valid DisallowedCombination> body) {
		return assignments.replaceDisallowedCombinations(actor(jwt), id, body);
	}

	private static UUID actor(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}

}
