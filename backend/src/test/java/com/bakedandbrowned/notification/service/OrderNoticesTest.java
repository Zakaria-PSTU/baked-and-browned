package com.bakedandbrowned.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.bakedandbrowned.notification.service.OrderNotices.Kind;
import com.bakedandbrowned.notification.service.OrderNotices.OrderFacts;

class OrderNoticesTest {

	// 2026-11-20 10:00-12:00 UTC = 4:00-6:00 PM in Dhaka (UTC+6).
	private static final Instant START = Instant.parse("2026-11-20T10:00:00Z");

	private static final Instant END = Instant.parse("2026-11-20T12:00:00Z");

	private static OrderFacts facts(String method, Instant dueBy, BigDecimal refund) {
		return new OrderFacts("BB-20261118-000123", method, new BigDecimal("1310.00"), START, END, dueBy, refund);
	}

	@Test
	void codOrdersAreReceivedWithTotalAndSlotInDhakaTime() {
		assertThat(OrderNotices.text(Kind.ORDER_RECEIVED, facts("COD", null, null))).isEqualTo(
				"Baked & Browned: order BB-20261118-000123 received. Total BDT 1,310.00, cash on delivery. "
						+ "Delivery Fri 20 Nov, 4:00 PM-6:00 PM.");
	}

	@Test
	void unpaidOrdersAskForPaymentBeforeTheHoldEnds() {
		Instant dueBy = Instant.parse("2026-11-18T09:45:00Z"); // 3:45 PM Dhaka
		assertThat(OrderNotices.text(Kind.ORDER_RECEIVED, facts("MANUAL", dueBy, null))).isEqualTo(
				"Baked & Browned: order BB-20261118-000123 received. Please pay BDT 1,310.00 by 3:45 PM to keep "
						+ "your delivery slot.");
	}

	@Test
	void aLateReceivedNoticeForAPaidOrderDoesNotAskForPayment() {
		assertThat(OrderNotices.text(Kind.ORDER_RECEIVED, facts("ONLINE", null, null)))
			.isEqualTo("Baked & Browned: order BB-20261118-000123 received. Delivery Fri 20 Nov, 4:00 PM-6:00 PM.");
	}

	@Test
	void confirmationCancellationAndExpiry() {
		assertThat(OrderNotices.text(Kind.ORDER_CONFIRMED, facts("MANUAL", null, null))).isEqualTo(
				"Baked & Browned: payment received. Order BB-20261118-000123 is confirmed for delivery Fri 20 Nov, "
						+ "4:00 PM-6:00 PM.");
		assertThat(OrderNotices.text(Kind.ORDER_CANCELLED, facts("COD", null, null)))
			.isEqualTo("Baked & Browned: order BB-20261118-000123 has been cancelled.");
		assertThat(OrderNotices.text(Kind.ORDER_CANCELLED, facts("ONLINE", null, new BigDecimal("1310.00"))))
			.isEqualTo("Baked & Browned: order BB-20261118-000123 has been cancelled. Your refund of BDT 1,310.00 "
					+ "is being processed.");
		assertThat(OrderNotices.text(Kind.ORDER_EXPIRED, facts("ONLINE", null, null))).isEqualTo(
				"Baked & Browned: order BB-20261118-000123 was cancelled because payment was not completed in time.");
	}

	@ParameterizedTest
	@EnumSource(Kind.class)
	void everyNoticeFitsOneAsciiSmsEvenWithLargeAmountsAndMorningSlots(Kind kind) {
		// Largest money value and a slot crossing noon: the longest texts these templates produce.
		OrderFacts large = new OrderFacts("BB-20261118-999999", "MANUAL", new BigDecimal("9999999999.99"),
				Instant.parse("2026-12-30T04:30:00Z"), Instant.parse("2026-12-30T06:30:00Z"),
				Instant.parse("2026-12-29T05:59:00Z"), new BigDecimal("9999999999.99"));

		String text = OrderNotices.text(kind, large);

		assertThat(text).hasSizeLessThanOrEqualTo(OrderNotices.MAX_LENGTH).matches("[\\x20-\\x7E]+");
	}

}
