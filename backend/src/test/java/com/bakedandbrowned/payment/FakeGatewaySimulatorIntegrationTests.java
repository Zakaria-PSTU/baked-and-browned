package com.bakedandbrowned.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.bakedandbrowned.ordering.CheckoutIntegrationTest;
import com.bakedandbrowned.payment.gateway.GatewayPaymentStatus;
import com.bakedandbrowned.payment.gateway.fake.FakePaymentGateway;

/**
 * The dev-only hosted page of the fake gateway: finishing a test payment plays the provider's part,
 * so the order changes only through the verified callback, and a lost callback changes nothing.
 */
class FakeGatewaySimulatorIntegrationTests extends CheckoutIntegrationTest {

	@Autowired
	private FakePaymentGateway gateway;

	private String customer;

	private UUID orderId;

	private String session;

	@BeforeEach
	void onlineOrderOnTheHostedPage() throws Exception {
		customer = bearer("CUSTOMER");
		orderId = placeOrder(customer, "ONLINE", slot, line(fudge, 1, six));
		mockMvc.perform(post("/api/v1/payments/{id}/initiate", orderId).header(HttpHeaders.AUTHORIZATION, customer))
			.andExpect(status().isOk());
		session = jdbc.queryForObject("SELECT provider_session_id FROM payments WHERE order_id = ? AND superseded_at IS NULL",
				String.class, orderId);
	}

	@Test
	void showsTheTestPaymentWithWhereTheBrowserGoesBack() throws Exception {
		mockMvc.perform(get("/api/v1/dev/fake-gateway/sessions/{id}", session).header(HttpHeaders.AUTHORIZATION, customer))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.sessionId").value(session))
			.andExpect(jsonPath("$.orderNumber").value(orderNumber()))
			.andExpect(jsonPath("$.amount").value(1310.00))
			.andExpect(jsonPath("$.currency").value("BDT"))
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andExpect(jsonPath("$.returnUrl").value("http://localhost:8080/api/v1/payments/" + orderId + "/return"));
	}

	@Test
	void payingConfirmsTheOrderThroughTheVerifiedCallbackOnce() throws Exception {
		finish("SUCCEEDED", null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"));

		assertThat(orderStatus(orderId)).isEqualTo("CONFIRMED");
		assertThat(paymentStatus()).isEqualTo("SUCCEEDED");
		assertThat(count("payment_webhook_events")).isEqualTo(1);

		finish("FAILED", null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SESSION_FINISHED"));
		assertThat(orderStatus(orderId)).isEqualTo("CONFIRMED");
	}

	@Test
	void aFailedOrCancelledPaymentLeavesTheOrderPayable() throws Exception {
		finish("FAILED", true).andExpect(status().isOk());

		assertThat(orderStatus(orderId)).isEqualTo("PENDING_PAYMENT");
		assertThat(paymentStatus()).isEqualTo("FAILED");
	}

	@Test
	void aLostCallbackChangesNothingUntilReconciliationAsks() throws Exception {
		finish("SUCCEEDED", false).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"));

		assertThat(orderStatus(orderId)).isEqualTo("PENDING_PAYMENT");
		assertThat(paymentStatus()).isEqualTo("PENDING");
		assertThat(count("payment_webhook_events")).isZero();
		assertThat(gateway.queryStatus(session).status()).isEqualTo(GatewayPaymentStatus.SUCCEEDED);
	}

	@Test
	void refusesUnknownSessionsNoOutcomeAndAnonymousCallers() throws Exception {
		mockMvc.perform(get("/api/v1/dev/fake-gateway/sessions/{id}", "fake_sess_nope")
			.header(HttpHeaders.AUTHORIZATION, customer))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
		finish("PENDING", null).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("INVALID_OUTCOME"));
		mockMvc.perform(get("/api/v1/dev/fake-gateway/sessions/{id}", session)).andExpect(status().isUnauthorized());
		assertThat(paymentStatus()).isEqualTo("PENDING");
	}

	private ResultActions finish(String outcome, Boolean notifyShop) throws Exception {
		String body = notifyShop == null ? "{\"outcome\":\"" + outcome + "\"}"
				: "{\"outcome\":\"" + outcome + "\",\"notifyShop\":" + notifyShop + "}";
		return mockMvc.perform(post("/api/v1/dev/fake-gateway/sessions/{id}/finish", session)
			.header(HttpHeaders.AUTHORIZATION, customer)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private String paymentStatus() {
		return jdbc.queryForObject(
				"SELECT status::text FROM payments WHERE order_id = ? AND superseded_at IS NULL", String.class, orderId);
	}

	private String orderNumber() {
		return jdbc.queryForObject("SELECT order_number FROM orders WHERE id = ?", String.class, orderId);
	}

}
