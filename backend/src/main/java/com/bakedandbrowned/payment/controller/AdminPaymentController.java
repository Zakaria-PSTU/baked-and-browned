package com.bakedandbrowned.payment.controller;

import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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

import com.bakedandbrowned.common.web.PageResponse;
import com.bakedandbrowned.payment.dto.PaymentDtos.AdminPayment;
import com.bakedandbrowned.payment.dto.PaymentDtos.PaymentVerificationRequest;
import com.bakedandbrowned.payment.service.AdminPaymentQueries;
import com.bakedandbrowned.payment.service.AdminPaymentQueries.Proof;
import com.bakedandbrowned.payment.service.PaymentVerification;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Contract tag {@code Admin}: payment queue, proof download and verification. ADMIN only. */
@RestController
@RequestMapping("/api/v1/admin/payments")
@PreAuthorize("hasRole('ADMIN')")
public class AdminPaymentController {

	private final AdminPaymentQueries queries;

	private final PaymentVerification verification;

	AdminPaymentController(AdminPaymentQueries queries, PaymentVerification verification) {
		this.queries = queries;
		this.verification = verification;
	}

	@GetMapping
	PageResponse<AdminPayment> list(@RequestParam(name = "status", required = false) @Nullable PaymentStatus status,
			@RequestParam(name = "method", required = false) @Nullable PaymentMethod method,
			@RequestParam(name = "awaitingVerification", defaultValue = "false") boolean awaitingVerification,
			@RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
			@RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size) {
		return queries.list(status == null ? null : status.name(), method == null ? null : method.name(),
				awaitingVerification, page, size);
	}

	/**
	 * Always an attachment with the detected content type, never rendered inline; {@code nosniff}
	 * is set for every response by Spring Security.
	 */
	@GetMapping("/{paymentId}/proof")
	ResponseEntity<byte[]> proof(@PathVariable("paymentId") UUID paymentId) {
		Proof proof = queries.proof(paymentId);
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType(proof.contentType()))
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.attachment().filename(proof.fileName()).build().toString())
			.cacheControl(CacheControl.noStore())
			.body(proof.content());
	}

	@PostMapping("/{paymentId}/verification")
	AdminPayment verify(@AuthenticationPrincipal Jwt jwt, @PathVariable("paymentId") UUID paymentId,
			@Valid @RequestBody PaymentVerificationRequest body) {
		return verification.verify(paymentId, UUID.fromString(jwt.getSubject()), body.decision(), body.note());
	}

	/** Contract {@code PaymentStatus} (query filter). */
	enum PaymentStatus {
		PENDING, SUCCEEDED, FAILED, CANCELLED, REFUNDED
	}

	/** Contract {@code PaymentMethod} (query filter). */
	enum PaymentMethod {
		COD, MANUAL, ONLINE
	}

}
