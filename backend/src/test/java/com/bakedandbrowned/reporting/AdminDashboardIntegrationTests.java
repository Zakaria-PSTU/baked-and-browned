package com.bakedandbrowned.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.bakedandbrowned.common.value.Money;
import com.bakedandbrowned.ordering.CheckoutIntegrationTest;
import com.bakedandbrowned.reporting.dto.DashboardDtos.DashboardSummary;
import com.bakedandbrowned.reporting.service.DashboardQueries;

/**
 * The ADMIN dashboard summary (contract {@code adminDashboardSummary}): which orders and payments
 * count, the Asia/Dhaka day boundaries, the operational queues, and ADMIN-only access.
 */
class AdminDashboardIntegrationTests extends CheckoutIntegrationTest {

	/** 12:00 in Dhaka (UTC+6) on 2026-11-18; that Dhaka day is [2026-11-17T18:00Z, 2026-11-18T18:00Z). */
	private static final Instant NOON_IN_DHAKA = Instant.parse("2026-11-18T06:00:00Z");

	private static final Instant DAY_START = Instant.parse("2026-11-17T18:00:00Z");

	private static final Instant NEXT_DAY_START = Instant.parse("2026-11-18T18:00:00Z");

	@Autowired
	private DashboardQueries dashboard;

	private String admin;

	private String customer;

	@BeforeEach
	void people() {
		admin = bearer("ADMIN");
		customer = bearer("CUSTOMER");
	}

	@Test
	void anEmptyShopReportsZeros() throws Exception {
		summary(admin).andExpect(status().isOk())
			.andExpect(jsonPath("$.ordersToday").value(0))
			.andExpect(jsonPath("$.revenueToday").value(0.0))
			.andExpect(jsonPath("$.pendingOrders").value(0))
			.andExpect(jsonPath("$.preparingOrders").value(0))
			.andExpect(jsonPath("$.collectedToday").value(0.0))
			.andExpect(jsonPath("$.awaitingPaymentVerification").value(0))
			.andExpect(jsonPath("$.openReconciliationCases").value(0))
			.andExpect(jsonPath("$.lowStockIngredients").value(0));
	}

	@Test
	void onlyBookedOrdersCountTowardsTodaysOrdersAndRevenue() throws Exception {
		UUID confirmed = placeOrder(customer, "COD", slot, line(fudge, 1, six)); // 1250 + 60 delivery
		UUID preparing = placeOrder(customer, "COD", slot, line(cookies, 2)); // 1200 + 60
		placeOrder(customer, "MANUAL", slot, line(fudge, 1, six)); // PENDING_PAYMENT
		UUID cancelled = placeOrder(customer, "COD", slot, line(cookies, 1));
		cancel(customer, cancelled, null).andExpect(status().isOk());
		UUID expired = placeOrder(customer, "ONLINE", slot, line(cookies, 1));
		jdbc.update("UPDATE orders SET status = 'EXPIRED' WHERE id = ?", expired);
		jdbc.update("UPDATE orders SET status = 'PREPARING' WHERE id = ?", preparing);
		assertThat(orderStatus(confirmed)).isEqualTo("CONFIRMED");

		// The whole body as JSON, checked against the contract's shape: Money has two decimals.
		String body = summary(admin).andExpect(status().isOk())
			.andExpect(jsonPath("$.ordersToday").value(2))
			.andExpect(jsonPath("$.revenueToday").value(2570.00))
			.andExpect(jsonPath("$.pendingOrders").value(1))
			.andExpect(jsonPath("$.preparingOrders").value(1))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(body).contains("\"revenueToday\":2570.00", "\"collectedToday\":0.00");
	}

	@Test
	void todayIsTheAsiaDhakaCalendarDay() throws Exception {
		UUID atStart = placeOrder(customer, "COD", slot, line(fudge, 1, six));
		UUID justBefore = placeOrder(customer, "COD", slot, line(fudge, 1, six));
		UUID lastSecond = placeOrder(customer, "COD", slot, line(cookies, 1));
		UUID nextDay = placeOrder(customer, "COD", slot, line(cookies, 1));
		createdAt(atStart, DAY_START);
		createdAt(justBefore, DAY_START.minusMillis(1));
		createdAt(lastSecond, NEXT_DAY_START.minusSeconds(1));
		createdAt(nextDay, NEXT_DAY_START);

		DashboardSummary summary = dashboard.summary(NOON_IN_DHAKA);

		assertThat(summary.ordersToday()).isEqualTo(2);
		assertThat(summary.revenueToday()).isEqualTo(Money.of("1970.00")); // 1310 + 660
		// Current queues ignore the day: all four are CONFIRMED.
		assertThat(summary.pendingOrders()).isEqualTo(4);
		// 00:30 in Dhaka is still the previous UTC date, but already the new Dhaka day.
		assertThat(dashboard.summary(Instant.parse("2026-11-17T18:30:00Z")).ordersToday()).isEqualTo(2);
		assertThat(dashboard.summary(Instant.parse("2026-11-17T17:30:00Z")).ordersToday()).isEqualTo(1);
	}

	@Test
	void collectedTodayIsMoneyReceivedTodayGrossOfRefunds() throws Exception {
		UUID collected = placeOrder(customer, "COD", slot, line(fudge, 1, six));
		UUID refunded = placeOrder(customer, "COD", slot, line(cookies, 1));
		UUID yesterday = placeOrder(customer, "COD", slot, line(cookies, 1));
		placeOrder(customer, "COD", slot, line(cookies, 1)); // cash not collected yet
		for (UUID orderId : new UUID[] { collected, refunded, yesterday }) {
			verify(paymentOf(orderId)).andExpect(status().isOk());
		}
		jdbc.update("UPDATE payments SET status = 'REFUNDED' WHERE id = ?", paymentOf(refunded));
		jdbc.update("UPDATE payments SET verified_at = verified_at - interval '1 day' WHERE id = ?",
				paymentOf(yesterday));

		summary(admin).andExpect(status().isOk()).andExpect(jsonPath("$.collectedToday").value(1970.00));
	}

	@Test
	void awaitingVerificationIsTheManualQueue() throws Exception {
		UUID submitted = placeOrder(customer, "MANUAL", slot, line(fudge, 1, six));
		placeOrder(customer, "MANUAL", slot, line(cookies, 1)); // nothing submitted yet
		UUID approved = placeOrder(customer, "MANUAL", slot, line(cookies, 1));
		submit(submitted, "TXN-1").andExpect(status().isCreated());
		submit(approved, "TXN-2").andExpect(status().isCreated());
		verify(paymentOf(approved)).andExpect(status().isOk());

		summary(admin).andExpect(status().isOk()).andExpect(jsonPath("$.awaitingPaymentVerification").value(1));
		mockMvc
			.perform(get("/api/v1/admin/payments").param("awaitingVerification", "true")
				.header(HttpHeaders.AUTHORIZATION, admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(1));
	}

	@Test
	void openReconciliationCasesAreCounted() throws Exception {
		UUID open = placeOrder(customer, "MANUAL", slot, line(cookies, 1));
		UUID resolved = placeOrder(customer, "MANUAL", slot, line(cookies, 1));
		jdbc.update("""
				INSERT INTO payment_reconciliation_cases (payment_id, order_id, case_type) VALUES (?, ?, 'LATE_SUCCESS')""",
				paymentOf(open), open);
		jdbc.update("""
				INSERT INTO payment_reconciliation_cases (payment_id, order_id, case_type, status, resolution,
				  resolved_by, resolved_at, resolution_note)
				VALUES (?, ?, 'LATE_SUCCESS', 'RESOLVED', 'NO_ACTION', ?, now(), 'Checked')""", paymentOf(resolved),
				resolved, userIdOf(admin));

		summary(admin).andExpect(status().isOk()).andExpect(jsonPath("$.openReconciliationCases").value(1));
	}

	@Test
	void lowStockIsAvailableStockAtOrBelowTheThreshold() throws Exception {
		UUID butter = ingredient("Butter", "g", "500");
		UUID flour = ingredient("Flour", "g", "500");
		ingredient("Sugar", "g", "0"); // no threshold: never low
		jdbc.update("UPDATE ingredients SET low_stock_threshold = 500 WHERE id = ?", butter); // exactly at it
		jdbc.update("UPDATE ingredients SET low_stock_threshold = 499 WHERE id = ?", flour);
		jdbc.update("UPDATE ingredients SET low_stock_threshold = 850 WHERE id = ?", chocolate);

		summary(admin).andExpect(status().isOk()).andExpect(jsonPath("$.lowStockIngredients").value(1));
		// Reserving 200 g leaves 800 g of 1000 g available, below the 850 g threshold.
		placeOrder(customer, "COD", slot, line(fudge, 2, six));
		summary(admin).andExpect(status().isOk()).andExpect(jsonPath("$.lowStockIngredients").value(2));
	}

	@Test
	void onlyAdminsMayReadTheSummary() throws Exception {
		summary(customer).andExpect(status().isForbidden());
		mockMvc.perform(get("/api/v1/admin/dashboard/summary")).andExpect(status().isUnauthorized());
	}

	private ResultActions summary(String bearer) throws Exception {
		return mockMvc.perform(get("/api/v1/admin/dashboard/summary").header(HttpHeaders.AUTHORIZATION, bearer));
	}

	private ResultActions verify(UUID paymentId) throws Exception {
		return mockMvc.perform(post("/api/v1/admin/payments/{id}/verification", paymentId)
			.header(HttpHeaders.AUTHORIZATION, admin)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"decision\":\"APPROVE\",\"note\":\"Received\"}"));
	}

	private ResultActions submit(UUID orderId, String reference) throws Exception {
		return mockMvc.perform(multipart("/api/v1/payments/{orderId}/manual-submission", orderId)
			.param("provider", "BKASH")
			.param("transactionReference", reference)
			.header(HttpHeaders.AUTHORIZATION, customer));
	}

	private UUID paymentOf(UUID orderId) {
		return jdbc.queryForObject("SELECT id FROM payments WHERE order_id = ? AND superseded_at IS NULL", UUID.class,
				orderId);
	}

	private void createdAt(UUID orderId, Instant at) {
		jdbc.update("UPDATE orders SET created_at = ? WHERE id = ?", Timestamp.from(at), orderId);
	}

}
