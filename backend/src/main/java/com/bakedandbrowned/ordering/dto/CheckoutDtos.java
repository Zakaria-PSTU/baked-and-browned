package com.bakedandbrowned.ordering.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.bakedandbrowned.common.value.Money;
import com.bakedandbrowned.common.value.PhoneNumber;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Checkout request and quote shapes. There is deliberately no price, fee or total in any request
 * type: the server computes them all, and anything else a client sends is ignored.
 */
public final class CheckoutDtos {

	/** Sanity bound on lines per cart (not in the contract). */
	public static final int MAX_LINES = 50;

	private CheckoutDtos() {
	}

	/** Contract {@code DeliveryAddress}; {@code area} is a delivery zone code. */
	public record DeliveryAddress(@NotBlank @Size(max = 150) String name, @NotNull PhoneNumber phone,
			@NotBlank @Size(max = 120) String area, @NotBlank @Size(min = 5, max = 500) String address,
			@Nullable @Size(max = 500) String notes) {

		/** Addresses are personal data: never print them. */
		@Override
		public String toString() {
			return "DeliveryAddress[area=" + area + "]";
		}

	}

	/** Contract {@code CartLine}: {@code customTexts} maps a TEXT group id to the customer's message. */
	public record CartLine(@NotNull UUID productId, @NotNull @Min(1) @Max(50) Integer quantity,
			@NotNull List<@NotNull UUID> selectedOptionIds,
			@NotNull Map<@NotNull UUID, @NotNull @Size(max = 250) String> customTexts) {
	}

	/** Contract {@code CheckoutQuoteRequest}; checkout adds a payment method in 2F. */
	public record CheckoutQuoteRequest(@NotNull UUID deliverySlotId, @NotNull @Valid DeliveryAddress delivery,
			@NotNull @Size(min = 1, max = MAX_LINES) List<@NotNull @Valid CartLine> items) {
	}

	/** Contract {@code CheckoutQuote}. */
	public record QuoteResponse(Money subtotal, Money deliveryFee, Money discount, Money total, String currency) {
	}

}
