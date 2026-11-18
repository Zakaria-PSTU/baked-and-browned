package com.bakedandbrowned.delivery.controller;

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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.bakedandbrowned.delivery.dto.DeliveryZoneDtos.AdminDeliveryZoneResponse;
import com.bakedandbrowned.delivery.dto.DeliveryZoneDtos.DeliveryZoneResponse;
import com.bakedandbrowned.delivery.dto.DeliveryZoneDtos.DeliveryZoneWrite;
import com.bakedandbrowned.delivery.service.DeliveryZoneService;

import jakarta.validation.Valid;

/** Public {@code GET /delivery-zones} and the ADMIN zone endpoints. */
@RestController
public class DeliveryZoneController {

	private final DeliveryZoneService zones;

	DeliveryZoneController(DeliveryZoneService zones) {
		this.zones = zones;
	}

	@GetMapping("/api/v1/delivery-zones")
	List<DeliveryZoneResponse> selectable() {
		return zones.selectable();
	}

	@GetMapping("/api/v1/admin/delivery-zones")
	@PreAuthorize("hasRole('ADMIN')")
	List<AdminDeliveryZoneResponse> all() {
		return zones.all();
	}

	@PostMapping("/api/v1/admin/delivery-zones")
	@PreAuthorize("hasRole('ADMIN')")
	ResponseEntity<AdminDeliveryZoneResponse> create(@AuthenticationPrincipal Jwt jwt,
			@Valid @RequestBody DeliveryZoneWrite body) {
		return ResponseEntity.status(HttpStatus.CREATED).body(zones.create(UUID.fromString(jwt.getSubject()), body));
	}

	@PatchMapping("/api/v1/admin/delivery-zones/{zoneId}")
	@PreAuthorize("hasRole('ADMIN')")
	AdminDeliveryZoneResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable("zoneId") UUID id,
			@Valid @RequestBody DeliveryZoneWrite body) {
		return zones.update(UUID.fromString(jwt.getSubject()), id, body);
	}

}
