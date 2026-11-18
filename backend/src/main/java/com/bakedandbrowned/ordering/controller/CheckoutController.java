package com.bakedandbrowned.ordering.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bakedandbrowned.ordering.dto.CheckoutDtos.CheckoutQuoteRequest;
import com.bakedandbrowned.ordering.dto.CheckoutDtos.QuoteResponse;
import com.bakedandbrowned.ordering.service.QuoteService;

import jakarta.validation.Valid;

/** Contract tag {@code Checkout}. Requires a signed-in user (default-deny security rule). */
@RestController
@RequestMapping("/api/v1/checkout")
public class CheckoutController {

	private final QuoteService quotes;

	CheckoutController(QuoteService quotes) {
		this.quotes = quotes;
	}

	@PostMapping("/quote")
	QuoteResponse quote(@Valid @RequestBody CheckoutQuoteRequest body) {
		return quotes.quote(body);
	}

}
