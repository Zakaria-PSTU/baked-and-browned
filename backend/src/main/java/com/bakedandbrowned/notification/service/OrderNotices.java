package com.bakedandbrowned.notification.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

import com.bakedandbrowned.common.time.BusinessTime;

/**
 * Customer SMS texts for order events. Plain ASCII (one GSM-7 segment of at most 160 characters),
 * times in Asia/Dhaka, and only system values: never names, addresses, notes or gift messages that
 * a customer typed (business rules: messages are never rendered as markup, and are not echoed).
 */
public final class OrderNotices {

	public static final int MAX_LENGTH = 160;

	private static final String BRAND = "Baked & Browned: ";

	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
		.withZone(BusinessTime.ZONE);

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
		.withZone(BusinessTime.ZONE);

	private OrderNotices() {
	}

	/** Matches the {@code notifications.kind} check constraint. */
	public enum Kind {
		ORDER_RECEIVED, ORDER_CONFIRMED, ORDER_DISPATCHED, ORDER_CANCELLED, ORDER_EXPIRED, REFUND_COMPLETED
	}

	/**
	 * What a notice may say about an order.
	 *
	 * @param paymentDueBy end of the payment hold (ONLINE/MANUAL awaiting payment), else null
	 * @param refundAmount the refund the notice is about (completed), or an open request (cancelled), else null
	 */
	public record OrderFacts(String orderNumber, String paymentMethod, BigDecimal total, Instant slotStart,
			Instant slotEnd, @Nullable Instant paymentDueBy, @Nullable BigDecimal refundAmount) {
	}

	public static String text(Kind kind, OrderFacts order) {
		String text = switch (kind) {
			case ORDER_RECEIVED -> received(order);
			case ORDER_CONFIRMED -> BRAND + "payment received. Order " + order.orderNumber()
					+ " is confirmed for delivery " + slot(order) + ".";
			case ORDER_DISPATCHED -> BRAND + "order " + order.orderNumber() + " is on its way. Delivery " + slot(order) + ".";
			case ORDER_CANCELLED -> BRAND + "order " + order.orderNumber() + " has been cancelled."
					+ (order.refundAmount() == null ? ""
							: " Your refund of BDT " + amount(order.refundAmount()) + " is being processed.");
			case ORDER_EXPIRED -> BRAND + "order " + order.orderNumber()
					+ " was cancelled because payment was not completed in time.";
			case REFUND_COMPLETED -> BRAND + "your refund of BDT " + amount(requireRefund(order)) + " for order "
					+ order.orderNumber() + " has been sent. It may take a few days to reach your account.";
		};
		if (text.length() > MAX_LENGTH || !text.chars().allMatch(c -> c >= 0x20 && c < 0x7F)) {
			throw new IllegalStateException("Notice is not a single ASCII SMS segment: " + kind);
		}
		return text;
	}

	private static BigDecimal requireRefund(OrderFacts order) {
		if (order.refundAmount() == null) {
			throw new IllegalStateException("A refund notice needs the refund amount");
		}
		return order.refundAmount();
	}

	private static String received(OrderFacts order) {
		String start = BRAND + "order " + order.orderNumber() + " received. ";
		if ("COD".equals(order.paymentMethod())) {
			return start + "Total BDT " + amount(order.total()) + ", cash on delivery. Delivery " + slot(order) + ".";
		}
		if (order.paymentDueBy() != null) {
			return start + "Please pay BDT " + amount(order.total()) + " by " + TIME.format(order.paymentDueBy())
					+ " to keep your delivery slot.";
		}
		// Sent late, after the order was already paid or closed: no payment request.
		return start + "Delivery " + slot(order) + ".";
	}

	private static String slot(OrderFacts order) {
		return DAY.format(order.slotStart()) + ", " + TIME.format(order.slotStart()) + "-"
				+ TIME.format(order.slotEnd());
	}

	private static String amount(BigDecimal value) {
		return String.format(Locale.ROOT, "%,.2f", value);
	}

}
