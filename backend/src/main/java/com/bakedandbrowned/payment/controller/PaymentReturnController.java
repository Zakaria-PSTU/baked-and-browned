package com.bakedandbrowned.payment.controller;

import java.net.URI;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract {@code paymentReturn}: where a hosted payment page sends the customer's browser back
 * (SSLCommerz {@code success_url}, {@code fail_url} and {@code cancel_url}; its docs do not say
 * whether that is a GET or a form POST, so both are accepted).
 * <p>
 * Public and inert by design: it reads nothing the browser sends and changes nothing, it only
 * forwards to the web app's order page, which shows the order's state as the server knows it. A
 * redirect is never proof of payment; only the verified provider callback is.
 */
@RestController
public class PaymentReturnController {

	private final String webReturnUrl;

	PaymentReturnController(@Value("${app.payments.online.return-url}") String webReturnUrl) {
		this.webReturnUrl = webReturnUrl;
	}

	@RequestMapping(path = "/api/v1/payments/{orderId}/return", method = { RequestMethod.GET, RequestMethod.POST })
	ResponseEntity<Void> back(@PathVariable("orderId") UUID orderId) {
		return ResponseEntity.status(HttpStatus.SEE_OTHER)
			.header(HttpHeaders.LOCATION, URI.create(webReturnUrl.replace("{orderId}", orderId.toString())).toString())
			.build();
	}

}
