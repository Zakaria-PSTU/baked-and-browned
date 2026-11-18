package com.bakedandbrowned.payment.controller;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bakedandbrowned.payment.dto.PaymentDtos.Refund;
import com.bakedandbrowned.payment.dto.PaymentDtos.RefundCompletionRequest;
import com.bakedandbrowned.payment.dto.PaymentDtos.RefundDecisionRequest;
import com.bakedandbrowned.payment.dto.PaymentDtos.RefundRequest;
import com.bakedandbrowned.payment.service.RefundWorkflow;

import jakarta.validation.Valid;

/** Contract tag {@code Admin}: the refund workflow. ADMIN only. */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminRefundController {

	private final RefundWorkflow refunds;

	AdminRefundController(RefundWorkflow refunds) {
		this.refunds = refunds;
	}

	@PostMapping("/payments/{paymentId}/refunds")
	ResponseEntity<Refund> request(@AuthenticationPrincipal Jwt jwt, @PathVariable("paymentId") UUID paymentId,
			@Valid @RequestBody RefundRequest body) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(refunds.request(paymentId, actor(jwt), body.reason(), body.amount()));
	}

	@GetMapping("/refunds")
	List<Refund> list(@RequestParam(name = "status", required = false) @Nullable RefundStatus status) {
		return refunds.list(status == null ? null : status.name());
	}

	@PostMapping("/refunds/{refundId}/decision")
	Refund decide(@AuthenticationPrincipal Jwt jwt, @PathVariable("refundId") UUID refundId,
			@Valid @RequestBody RefundDecisionRequest body) {
		return refunds.decide(refundId, actor(jwt), body.decision(), body.note());
	}

	@PostMapping("/refunds/{refundId}/completion")
	Refund complete(@AuthenticationPrincipal Jwt jwt, @PathVariable("refundId") UUID refundId,
			@Valid @RequestBody RefundCompletionRequest body) {
		return refunds.complete(refundId, actor(jwt), body.externalReference());
	}

	private static UUID actor(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}

	/** Contract {@code RefundStatus} (query filter). */
	enum RefundStatus {
		REQUESTED, APPROVED, REJECTED, COMPLETED
	}

}
