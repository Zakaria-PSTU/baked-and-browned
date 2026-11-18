package com.bakedandbrowned.payment.gateway;

import java.net.URI;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.bakedandbrowned.common.value.Money;

/**
 * What a gateway needs to open a hosted checkout. The amount is the server-computed order total;
 * {@code returnUrl} is only where the customer's browser lands afterwards, never proof of payment.
 *
 * @param paymentId our {@code payments.id}, used as the provider-side idempotency/merchant reference
 * @param orderReference the customer-visible order number, echoed back in verified results
 * @param customer contact details some providers require on the hosted page (receipts, risk checks)
 */
public record PaymentSessionRequest(UUID paymentId, String orderReference, Money amount, URI returnUrl,
		PaymentCustomer customer) {

	public PaymentSessionRequest {
		Objects.requireNonNull(paymentId, "paymentId");
		Objects.requireNonNull(orderReference, "orderReference");
		Objects.requireNonNull(amount, "amount");
		Objects.requireNonNull(returnUrl, "returnUrl");
		Objects.requireNonNull(customer, "customer");
		if (amount.isZero()) {
			throw new IllegalArgumentException("A gateway session needs a positive amount");
		}
	}

	/**
	 * The order's delivery contact (its immutable snapshot) and the account email if any.
	 *
	 * @param phone normalized {@code +8801XXXXXXXXX}
	 */
	public record PaymentCustomer(String name, String phone, @Nullable String email, String address, String area) {

		public PaymentCustomer {
			Objects.requireNonNull(name, "name");
			Objects.requireNonNull(phone, "phone");
			Objects.requireNonNull(address, "address");
			Objects.requireNonNull(area, "area");
		}

		/** Customer details are personal data: never print them. */
		@Override
		public String toString() {
			return "PaymentCustomer[redacted]";
		}

	}

}
