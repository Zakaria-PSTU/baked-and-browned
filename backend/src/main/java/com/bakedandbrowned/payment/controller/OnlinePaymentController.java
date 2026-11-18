package com.bakedandbrowned.payment.controller;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bakedandbrowned.common.ratelimit.RateLimiter;
import com.bakedandbrowned.common.web.ApiException;
import com.bakedandbrowned.payment.dto.PaymentDtos.PaymentInitiation;
import com.bakedandbrowned.payment.gateway.PaymentGateway;
import com.bakedandbrowned.payment.gateway.PaymentGateways;
import com.bakedandbrowned.payment.gateway.PaymentVerificationException;
import com.bakedandbrowned.payment.gateway.ProviderCallback;
import com.bakedandbrowned.payment.gateway.VerifiedPaymentResult;
import com.bakedandbrowned.payment.service.GatewayResults;
import com.bakedandbrowned.payment.service.OnlinePaymentInitiation;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Contract tag {@code Payments}: hosted checkout for the order's customer, and the provider
 * callback. The callback is public (SecurityConfig) and untrusted: the adapter verifies it server
 * to server before anything changes, and the gateway call happens before the transaction that
 * applies the result.
 */
@RestController
@RequestMapping("/api/v1/payments")
public class OnlinePaymentController {

	public static final String UNKNOWN_PROVIDER = "UNKNOWN_PROVIDER";

	public static final String CALLBACK_NOT_VERIFIED = "CALLBACK_NOT_VERIFIED";

	private static final Logger log = LoggerFactory.getLogger(OnlinePaymentController.class);

	private final OnlinePaymentInitiation initiation;

	private final PaymentGateways gateways;

	private final GatewayResults results;

	private final RateLimiter rateLimiter;

	OnlinePaymentController(OnlinePaymentInitiation initiation, PaymentGateways gateways, GatewayResults results,
			RateLimiter rateLimiter) {
		this.initiation = initiation;
		this.gateways = gateways;
		this.results = results;
		this.rateLimiter = rateLimiter;
	}

	@PostMapping("/{orderId}/initiate")
	PaymentInitiation initiate(@AuthenticationPrincipal Jwt jwt, @PathVariable("orderId") UUID orderId) {
		rateLimiter.check("payment-initiation", jwt.getSubject());
		return initiation.initiate(orderId, UUID.fromString(jwt.getSubject()));
	}

	/** 200 once the callback is verified, whether it changed anything or was already applied. */
	@PostMapping(path = "/webhooks/{provider}",
			consumes = { MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_FORM_URLENCODED_VALUE })
	ResponseEntity<Void> webhook(@PathVariable("provider") String provider, @RequestBody String body,
			@Nullable @org.springframework.web.bind.annotation.RequestHeader(name = HttpHeaders.CONTENT_TYPE,
					required = false) String contentType,
			HttpServletRequest request) {
		rateLimiter.check("payment-webhook", request.getRemoteAddr());
		PaymentGateway gateway = gateways.byProvider(provider)
			.orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, UNKNOWN_PROVIDER, "Unknown payment provider."));
		VerifiedPaymentResult result;
		try {
			result = gateway.verify(new ProviderCallback(gateway.provider(), contentType, headers(request), body));
		}
		catch (PaymentVerificationException ex) {
			log.warn("Rejected {} callback: {}", gateway.provider(), ex.getMessage());
			throw new ApiException(HttpStatus.BAD_REQUEST, CALLBACK_NOT_VERIFIED, "The callback could not be verified.");
		}
		if (!gateway.provider().equals(result.provider())) {
			throw new IllegalStateException("Gateway " + gateway.provider() + " verified a result for another provider");
		}
		GatewayResults.Outcome outcome = results.apply(result);
		log.info("Applied {} callback for session {}: {}", gateway.provider(), result.sessionId(), outcome);
		return ResponseEntity.ok().build();
	}

	private static Map<String, String> headers(HttpServletRequest request) {
		Map<String, String> headers = new LinkedHashMap<>();
		for (String name : Collections.list(request.getHeaderNames())) {
			String value = request.getHeader(name);
			if (value != null) {
				headers.put(name, value);
			}
		}
		return headers;
	}

}
