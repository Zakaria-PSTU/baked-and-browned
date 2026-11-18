package com.bakedandbrowned.payment.service;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.bakedandbrowned.common.value.Money;
import com.bakedandbrowned.common.web.ApiException;
import com.bakedandbrowned.ordering.service.OrderPaymentOutcomes;
import com.bakedandbrowned.ordering.service.OrderPaymentOutcomes.CheckoutContact;
import com.bakedandbrowned.ordering.service.OrderPaymentOutcomes.PaymentOrder;
import com.bakedandbrowned.ordering.service.OrderQueryService;
import com.bakedandbrowned.payment.dto.PaymentDtos.PaymentInitiation;
import com.bakedandbrowned.payment.gateway.HostedPaymentSession;
import com.bakedandbrowned.payment.gateway.PaymentGateway;
import com.bakedandbrowned.payment.gateway.PaymentGateways;
import com.bakedandbrowned.payment.gateway.PaymentSessionRequest;
import com.bakedandbrowned.payment.gateway.PaymentSessionRequest.PaymentCustomer;

/**
 * Contract {@code initiatePayment}: opens (or reopens) the hosted checkout of an ONLINE order that
 * is still waiting for payment within its hold.
 * <ol>
 * <li>Short transaction, order row locked: checks the order; returns the open session of the
 * current payment if it has one; after a FAILED or CANCELLED attempt, supersedes it with a new
 * PENDING payment (one current payment per order).</li>
 * <li>No transaction, no locks: the gateway call (ADR-0006, ADR-0012).</li>
 * <li>Short transaction, order row locked again: stores the session on the payment, unless the
 * order closed meanwhile (409) or a concurrent request stored one first, which is then returned
 * instead (the extra provider session is never shown and lapses).</li>
 * </ol>
 */
@Service
public class OnlinePaymentInitiation {

	public static final String NOT_AN_ONLINE_PAYMENT = "NOT_AN_ONLINE_PAYMENT";

	public static final String INVALID_STATE = "INVALID_STATE";

	public static final String PAYMENT_HOLD_EXPIRED = "PAYMENT_HOLD_EXPIRED";

	public static final String ONLINE_PAYMENT_UNAVAILABLE = "ONLINE_PAYMENT_UNAVAILABLE";

	public static final String GATEWAY_UNAVAILABLE = "GATEWAY_UNAVAILABLE";

	private static final Logger log = LoggerFactory.getLogger(OnlinePaymentInitiation.class);

	private final JdbcTemplate jdbc;

	private final TransactionTemplate transactions;

	private final OrderPaymentOutcomes orders;

	private final PaymentGateways gateways;

	private final Clock clock;

	/** Public API origin, for the browser return endpoint the provider sends the customer to. */
	private final String apiBaseUrl;

	public OnlinePaymentInitiation(JdbcTemplate jdbc, TransactionTemplate transactions, OrderPaymentOutcomes orders,
			PaymentGateways gateways, Clock clock,
			@Value("${app.payments.online.api-base-url}") String apiBaseUrl) {
		this.jdbc = jdbc;
		this.transactions = transactions;
		this.orders = orders;
		this.gateways = gateways;
		this.clock = clock;
		this.apiBaseUrl = apiBaseUrl.endsWith("/") ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1) : apiBaseUrl;
	}

	public PaymentInitiation initiate(UUID orderId, UUID customerId) {
		PaymentGateway gateway = gateways.active()
			.orElseThrow(() -> ApiException.conflict(ONLINE_PAYMENT_UNAVAILABLE,
					"Online payment is not available right now. Please choose another payment method."));
		Prepared prepared = inTransaction(() -> prepare(orderId, customerId));
		if (prepared.openCheckout() != null) {
			return new PaymentInitiation(prepared.paymentId(), prepared.openCheckout());
		}
		HostedPaymentSession session;
		try {
			session = gateway.createSession(new PaymentSessionRequest(prepared.paymentId(), prepared.orderNumber(),
					prepared.amount(), browserReturn(orderId), prepared.customer()));
		}
		catch (RuntimeException ex) {
			// Nothing was stored; the customer can simply try again.
			log.warn("Payment gateway {} could not open a session for payment {}: {}", gateway.provider(),
					prepared.paymentId(), ex.toString());
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, GATEWAY_UNAVAILABLE,
					"The payment provider could not be reached. Please try again in a moment.");
		}
		return inTransaction(() -> store(orderId, prepared.paymentId(), gateway.provider(), session));
	}

	private Prepared prepare(UUID orderId, UUID customerId) {
		Instant now = clock.instant();
		PaymentOrder order = orders.lock(orderId, now)
			.filter(o -> o.userId().equals(customerId))
			.orElseThrow(() -> ApiException.notFound(OrderQueryService.ORDER_NOT_FOUND, "Order not found."));
		requirePayable(order);
		Map<String, Object> current = jdbc.queryForMap("""
				SELECT id, status::text AS status, amount, provider_checkout_url FROM payments
				WHERE order_id = ? AND superseded_at IS NULL FOR UPDATE""", orderId);
		UUID paymentId = (UUID) current.get("id");
		Money amount = Money.of((java.math.BigDecimal) current.get("amount"));
		String status = (String) current.get("status");
		switch (status) {
			case "PENDING" -> {
				String checkout = (String) current.get("provider_checkout_url");
				return new Prepared(paymentId, order.orderNumber(), amount, checkout == null ? null : URI.create(checkout),
						customer(orderId));
			}
			case "FAILED", "CANCELLED" -> {
				// A finished, unsuccessful attempt: the retry is a new payment record (V5: retries supersede).
				jdbc.update("UPDATE payments SET superseded_at = ? WHERE id = ?", Timestamp.from(now), paymentId);
				UUID retry = jdbc.queryForObject("""
						INSERT INTO payments (order_id, method, amount, status) VALUES (?, 'ONLINE', ?, 'PENDING')
						RETURNING id""", UUID.class, orderId, amount.amount());
				return new Prepared(retry, order.orderNumber(), amount, null, customer(orderId));
			}
			default -> throw ApiException.conflict(INVALID_STATE, "This order has already been paid.");
		}
	}

	private PaymentInitiation store(UUID orderId, UUID paymentId, String provider, HostedPaymentSession session) {
		PaymentOrder order = orders.lock(orderId, clock.instant()).orElseThrow();
		requirePayable(order);
		Map<String, Object> payment = jdbc.queryForMap("""
				SELECT status::text AS status, superseded_at IS NOT NULL AS superseded, provider_checkout_url
				FROM payments WHERE id = ? FOR UPDATE""", paymentId);
		if (Boolean.TRUE.equals(payment.get("superseded")) || !"PENDING".equals(payment.get("status"))) {
			throw ApiException.conflict(INVALID_STATE, "This payment attempt has changed. Please try again.");
		}
		String stored = (String) payment.get("provider_checkout_url");
		if (stored != null) {
			return new PaymentInitiation(paymentId, URI.create(stored));
		}
		jdbc.update("""
				UPDATE payments SET provider = ?, provider_session_id = ?, provider_checkout_url = ? WHERE id = ?""",
				provider, session.sessionId(), session.checkoutUrl().toString(), paymentId);
		return new PaymentInitiation(paymentId, session.checkoutUrl());
	}

	private static void requirePayable(PaymentOrder order) {
		if (!"ONLINE".equals(order.paymentMethod())) {
			throw ApiException.conflict(NOT_AN_ONLINE_PAYMENT, "This order is not paid online.");
		}
		if (!"PENDING_PAYMENT".equals(order.status())) {
			throw ApiException.conflict(INVALID_STATE, "This order is no longer waiting for payment.");
		}
		if (!order.holdOpen()) {
			throw ApiException.conflict(PAYMENT_HOLD_EXPIRED,
					"The time to pay for this order has run out. Please place a new order.");
		}
	}

	private <T> T inTransaction(java.util.function.Supplier<T> work) {
		T result = transactions.execute(status -> work.get());
		if (result == null) {
			throw new IllegalStateException("Transaction returned no result");
		}
		return result;
	}

	private PaymentCustomer customer(UUID orderId) {
		CheckoutContact contact = orders.checkoutContact(orderId);
		return new PaymentCustomer(contact.name(), contact.phone(), contact.email(), contact.address(), contact.area());
	}

	/** The provider sends the browser here; {@code PaymentReturnController} forwards it to the web app. */
	private URI browserReturn(UUID orderId) {
		return URI.create(apiBaseUrl + "/api/v1/payments/" + orderId + "/return");
	}

	private record Prepared(UUID paymentId, String orderNumber, Money amount, @Nullable URI openCheckout,
			PaymentCustomer customer) {
	}

}
