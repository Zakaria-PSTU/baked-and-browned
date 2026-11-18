package com.bakedandbrowned.scheduling.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bakedandbrowned.scheduling.dto.SlotDtos.AdminSlotResponse;
import com.bakedandbrowned.scheduling.dto.SlotDtos.SlotResponse;
import com.bakedandbrowned.scheduling.dto.SlotDtos.SlotUpdate;
import com.bakedandbrowned.scheduling.dto.SlotDtos.SlotWrite;
import com.bakedandbrowned.scheduling.service.SlotService;

import jakarta.validation.Valid;

/** Public {@code GET /delivery-slots} and the ADMIN slot endpoints. Dates are Asia/Dhaka days. */
@RestController
public class SlotController {

	private final SlotService slots;

	SlotController(SlotService slots) {
		this.slots = slots;
	}

	@GetMapping("/api/v1/delivery-slots")
	List<SlotResponse> bookable(@RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
		return slots.bookable(date);
	}

	@GetMapping("/api/v1/admin/delivery-slots")
	@PreAuthorize("hasRole('ADMIN')")
	List<AdminSlotResponse> between(@RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return slots.between(from, to);
	}

	@PostMapping("/api/v1/admin/delivery-slots")
	@PreAuthorize("hasRole('ADMIN')")
	ResponseEntity<AdminSlotResponse> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SlotWrite body) {
		return ResponseEntity.status(HttpStatus.CREATED).body(slots.create(UUID.fromString(jwt.getSubject()), body));
	}

	@PatchMapping("/api/v1/admin/delivery-slots/{slotId}")
	@PreAuthorize("hasRole('ADMIN')")
	AdminSlotResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable("slotId") UUID id,
			@Valid @RequestBody SlotUpdate body) {
		return slots.update(UUID.fromString(jwt.getSubject()), id, body);
	}

}
