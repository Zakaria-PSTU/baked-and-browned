package com.bakedandbrowned.ordering.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bakedandbrowned.common.ratelimit.RateLimiter;
import com.bakedandbrowned.ordering.dto.OrderDtos.OrderDetail;
import com.bakedandbrowned.ordering.dto.OrderDtos.OrderSummary;
import com.bakedandbrowned.ordering.dto.OrderDtos.PlaceOrderRequest;
import com.bakedandbrowned.ordering.service.OrderPlacementService;
import com.bakedandbrowned.ordering.service.OrderQueryService;

import jakarta.validation.Valid;

/** Contract tag {@code Orders}. Signed-in users; customers see only their own orders. */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

	private final OrderPlacementService placement;

	private final OrderQueryService queries;

	private final RateLimiter rateLimiter;

	OrderController(OrderPlacementService placement, OrderQueryService queries, RateLimiter rateLimiter) {
		this.placement = placement;
		this.queries = queries;
		this.rateLimiter = rateLimiter;
	}

	/** 201 for a new order and for an idempotent replay alike (contract). */
	@PostMapping
	ResponseEntity<OrderDetail> place(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PlaceOrderRequest body) {
		rateLimiter.check("checkout", jwt.getSubject());
		return ResponseEntity.status(HttpStatus.CREATED).body(placement.place(userId(jwt), body));
	}

	@GetMapping
	List<OrderSummary> mine(@AuthenticationPrincipal Jwt jwt) {
		return queries.mine(userId(jwt));
	}

	@GetMapping("/{orderId}")
	OrderDetail detail(@AuthenticationPrincipal Jwt jwt, @PathVariable("orderId") UUID orderId) {
		return queries.detail(orderId, userId(jwt), isAdmin(jwt));
	}

	private static UUID userId(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}

	private static boolean isAdmin(Jwt jwt) {
		List<String> roles = jwt.getClaimAsStringList("roles");
		return roles != null && roles.contains("ADMIN");
	}

}
