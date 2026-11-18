package com.bakedandbrowned.ordering.controller;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bakedandbrowned.ordering.dto.AdminOrderDtos.AdminOrderDetail;
import com.bakedandbrowned.ordering.dto.AdminOrderDtos.AdminOrderStatusEvent;
import com.bakedandbrowned.ordering.dto.AdminOrderDtos.OrderStatus;
import com.bakedandbrowned.ordering.dto.AdminOrderDtos.StatusChangeRequest;
import com.bakedandbrowned.ordering.dto.OrderDtos.OrderSummary;
import com.bakedandbrowned.ordering.service.OrderAdminService;
import com.bakedandbrowned.ordering.service.OrderAdminService.Customer;
import com.bakedandbrowned.ordering.service.OrderQueryService;
import com.bakedandbrowned.payment.service.AdminPaymentQueries;

import jakarta.validation.Valid;

/**
 * Contract tag {@code Admin}: order list, detail, history and status changes. ADMIN only. The
 * detail combines the ordering and payment views here, so neither service depends on the other's.
 */
@RestController
@RequestMapping("/api/v1/admin/orders")
@PreAuthorize("hasRole('ADMIN')")
public class AdminOrderController {

	private final OrderQueryService queries;

	private final OrderAdminService admin;

	private final AdminPaymentQueries payments;

	AdminOrderController(OrderQueryService queries, OrderAdminService admin, AdminPaymentQueries payments) {
		this.queries = queries;
		this.admin = admin;
		this.payments = payments;
	}

	@GetMapping
	List<OrderSummary> list(@RequestParam(name = "status", required = false) @Nullable OrderStatus status) {
		return queries.all(status == null ? null : status.name());
	}

	@GetMapping("/{orderId}")
	AdminOrderDetail detail(@AuthenticationPrincipal Jwt jwt, @PathVariable("orderId") UUID orderId) {
		Customer customer = admin.customer(orderId);
		return new AdminOrderDetail(queries.detail(orderId, UUID.fromString(jwt.getSubject()), true),
				customer.userId(), customer.name(), customer.phone(), admin.history(orderId), payments.forOrder(orderId),
				payments.refunds(orderId));
	}

	@GetMapping("/{orderId}/status-history")
	List<AdminOrderStatusEvent> history(@PathVariable("orderId") UUID orderId) {
		return admin.history(orderId);
	}

	@PatchMapping("/{orderId}/status")
	OrderSummary changeStatus(@AuthenticationPrincipal Jwt jwt, @PathVariable("orderId") UUID orderId,
			@Valid @RequestBody StatusChangeRequest body) {
		return admin.changeStatus(orderId, UUID.fromString(jwt.getSubject()), body.status(), body.reason());
	}

}
