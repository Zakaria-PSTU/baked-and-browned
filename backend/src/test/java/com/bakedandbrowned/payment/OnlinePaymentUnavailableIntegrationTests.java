package com.bakedandbrowned.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.convention.TestBean;

import com.bakedandbrowned.ordering.CheckoutIntegrationTest;
import com.bakedandbrowned.ordering.service.OrderPlacementService;
import com.bakedandbrowned.payment.gateway.PaymentGateways;

/**
 * A deployment without an active gateway (for example prod before a merchant account exists): checkout
 * does not offer ONLINE, and an ONLINE order is refused before anything is reserved.
 */
class OnlinePaymentUnavailableIntegrationTests extends CheckoutIntegrationTest {

	@TestBean
	private PaymentGateways paymentGateways;

	static PaymentGateways paymentGateways() {
		return new PaymentGateways(List.of(), null);
	}

	@Test
	void onlineIsNotOffered() throws Exception {
		mockMvc.perform(get("/api/v1/payments/methods").header(HttpHeaders.AUTHORIZATION, bearer("CUSTOMER")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.available.length()").value(2))
			.andExpect(jsonPath("$.available[0]").value("COD"))
			.andExpect(jsonPath("$.available[1]").value("MANUAL"));
	}

	@Test
	void anOnlineOrderIsRefusedWithoutHoldingStockOrSlot() throws Exception {
		place(bearer("CUSTOMER"), order(UUID.randomUUID(), "ONLINE", slot, line(fudge, 1, six)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(OrderPlacementService.ONLINE_PAYMENT_UNAVAILABLE));
		assertThat(count("orders")).isZero();
		assertThat(reservedUnits(slot)).isZero();
		assertThat(reservedStock(chocolate)).isEqualByComparingTo(BigDecimal.ZERO);
	}

	@Test
	void otherMethodsStillWork() throws Exception {
		UUID orderId = placeOrder(bearer("CUSTOMER"), "COD", slot, line(fudge, 1, six));
		assertThat(orderStatus(orderId)).isEqualTo("CONFIRMED");
	}

}
