package com.bakedandbrowned.ordering.service;

import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bakedandbrowned.common.web.ApiException;
import com.bakedandbrowned.inventory.service.StockReservations;
import com.bakedandbrowned.ordering.dto.CheckoutDtos.CheckoutQuoteRequest;
import com.bakedandbrowned.ordering.dto.CheckoutDtos.QuoteResponse;

/**
 * {@code POST /checkout/quote}: a nonbinding, server-calculated price. Also tells the customer early
 * if the slot is full or stock has run out, without reserving anything; order placement checks and
 * reserves again atomically.
 */
@Service
public class QuoteService {

	public static final String SLOT_FULL = "SLOT_FULL";

	public static final String OUT_OF_STOCK = "OUT_OF_STOCK";

	private final CartPricer pricer;

	private final StockReservations stock;

	private final Clock clock;

	public QuoteService(CartPricer pricer, StockReservations stock, Clock clock) {
		this.pricer = pricer;
		this.stock = stock;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public QuoteResponse quote(CheckoutQuoteRequest request) {
		PricedCart cart = pricer.price(request, clock.instant());
		if (cart.capacityUnits() > cart.slot().remainingUnits()) {
			throw ApiException.unprocessable(SLOT_FULL,
					"This delivery slot cannot take an order this size. Please choose another slot.");
		}
		if (!stock.available(cart.ingredientNeeds())) {
			throw ApiException.unprocessable(OUT_OF_STOCK,
					"Some items in your cart are not available in that quantity right now.");
		}
		return new QuoteResponse(cart.subtotal(), cart.deliveryFee(), cart.discount(), cart.total(), "BDT");
	}

}
