package com.bakedandbrowned.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import com.bakedandbrowned.ordering.CheckoutIntegrationTest;

/**
 * Contract {@code listPaymentMethods} in the shared test context: the fake gateway is active and one
 * bKash receiving account is configured, so every method is offered.
 */
class PaymentMethodsIntegrationTests extends CheckoutIntegrationTest {

	@Test
	void signedInCustomersSeeEveryConfiguredMethodInDisplayOrder() throws Exception {
		mockMvc.perform(get("/api/v1/payments/methods").header(HttpHeaders.AUTHORIZATION, bearer("CUSTOMER")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.available.length()").value(3))
			.andExpect(jsonPath("$.available[0]").value("COD"))
			.andExpect(jsonPath("$.available[1]").value("MANUAL"))
			.andExpect(jsonPath("$.available[2]").value("ONLINE"));
		mockMvc.perform(get("/api/v1/payments/methods")).andExpect(status().isUnauthorized());
	}

	@Test
	void onlineOrdersArePlacedWhileAGatewayIsActive() throws Exception {
		UUID orderId = placeOrder(bearer("CUSTOMER"), "ONLINE", slot, line(fudge, 1, six));
		assertThat(orderStatus(orderId)).isEqualTo("PENDING_PAYMENT");
	}

}
