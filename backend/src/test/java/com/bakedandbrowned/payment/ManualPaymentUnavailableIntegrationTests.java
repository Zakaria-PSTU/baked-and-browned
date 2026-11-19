package com.bakedandbrowned.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.convention.TestBean;

import com.bakedandbrowned.ordering.CheckoutIntegrationTest;
import com.bakedandbrowned.ordering.service.OrderPlacementService;
import com.bakedandbrowned.payment.service.ManualPaymentProperties;

/**
 * A deployment without receiving accounts (the default, ADR-0022): checkout does not offer MANUAL,
 * and a MANUAL order is refused before anything is reserved.
 */
class ManualPaymentUnavailableIntegrationTests extends CheckoutIntegrationTest {

	@TestBean
	private ManualPaymentProperties manualPaymentProperties;

	static ManualPaymentProperties manualPaymentProperties() {
		return new ManualPaymentProperties(null);
	}

	@Test
	void manualIsNotOffered() throws Exception {
		mockMvc.perform(get("/api/v1/payments/methods").header(HttpHeaders.AUTHORIZATION, bearer("CUSTOMER")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.available.length()").value(2))
			.andExpect(jsonPath("$.available[0]").value("COD"))
			.andExpect(jsonPath("$.available[1]").value("ONLINE"));
	}

	@Test
	void aManualOrderIsRefusedWithoutHoldingStockOrSlot() throws Exception {
		place(bearer("CUSTOMER"), order(UUID.randomUUID(), "MANUAL", slot, line(fudge, 1, six)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(OrderPlacementService.MANUAL_PAYMENT_UNAVAILABLE));
		assertThat(count("orders")).isZero();
		assertThat(reservedUnits(slot)).isZero();
		assertThat(reservedStock(chocolate)).isEqualByComparingTo(BigDecimal.ZERO);
	}

	@Test
	void otherMethodsStillWork() throws Exception {
		UUID cod = placeOrder(bearer("CUSTOMER"), "COD", slot, line(fudge, 1, six));
		UUID online = placeOrder(bearer("CUSTOMER"), "ONLINE", slot, line(fudge, 1, six));
		assertThat(orderStatus(cod)).isEqualTo("CONFIRMED");
		assertThat(orderStatus(online)).isEqualTo("PENDING_PAYMENT");
	}

}
