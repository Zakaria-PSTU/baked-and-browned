package com.bakedandbrowned.payment.controller;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.bakedandbrowned.common.ratelimit.RateLimiter;
import com.bakedandbrowned.payment.dto.PaymentDtos.ManualPaymentAccount;
import com.bakedandbrowned.payment.dto.PaymentDtos.ManualPaymentSubmission;
import com.bakedandbrowned.payment.service.ManualPaymentProperties;
import com.bakedandbrowned.payment.service.ManualPaymentSubmissions;

/** Contract tag {@code Payments}: manual payment evidence from the order's customer. */
@RestController
@EnableConfigurationProperties(ManualPaymentProperties.class)
@RequestMapping("/api/v1/payments")
public class ManualPaymentController {

	private final ManualPaymentSubmissions submissions;

	private final RateLimiter rateLimiter;

	private final List<ManualPaymentAccount> accounts;

	ManualPaymentController(ManualPaymentSubmissions submissions, RateLimiter rateLimiter,
			ManualPaymentProperties properties) {
		this.submissions = submissions;
		this.rateLimiter = rateLimiter;
		this.accounts = properties.accounts()
			.stream()
			.map(a -> new ManualPaymentAccount(a.provider(), a.label(), a.accountName(), a.accountNumber(), a.note()))
			.toList();
	}

	/** Where to send a MANUAL payment (ADR-0022); empty when manual payment is not offered. */
	@GetMapping("/manual-accounts")
	List<ManualPaymentAccount> accounts() {
		return accounts;
	}

	/** Fields are validated by the service, so every invalid value is the same 422 problem. */
	@PostMapping(path = "/{orderId}/manual-submission", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	ResponseEntity<ManualPaymentSubmission> submit(@AuthenticationPrincipal Jwt jwt,
			@PathVariable("orderId") UUID orderId,
			@RequestParam(name = "provider", required = false) @Nullable String provider,
			@RequestParam(name = "transactionReference", required = false) @Nullable String transactionReference,
			@RequestPart(name = "proof", required = false) @Nullable MultipartFile proof) {
		rateLimiter.check("manual-payment", jwt.getSubject());
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(submissions.submit(orderId, UUID.fromString(jwt.getSubject()), provider, transactionReference,
					proof));
	}

}
