package com.bakedandbrowned.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import com.bakedandbrowned.common.outbox.OutboxDispatcher;
import com.bakedandbrowned.common.outbox.OutboxHandler;
import com.bakedandbrowned.common.outbox.OutboxMessage;
import com.bakedandbrowned.notification.sms.FakeSmsSender;
import com.bakedandbrowned.notification.sms.SmsSender;
import com.bakedandbrowned.notification.sms.SmsSender.SmsMessage;
import com.bakedandbrowned.ordering.CheckoutIntegrationTest;
import com.bakedandbrowned.ordering.service.HoldExpiryJob;
import com.bakedandbrowned.ordering.service.OrderEvents;

/**
 * Customer SMS notices delivered from the outbox (business rules, Notifications): one per event,
 * to the account holder, retried on provider failure without duplicates, and every order event the
 * application publishes has a handler.
 */
class OrderNotificationIntegrationTests extends CheckoutIntegrationTest {

	@Autowired
	private OutboxDispatcher dispatcher;

	@Autowired
	private FakeSmsSender sms;

	@Autowired
	private List<OutboxHandler> handlers;

	@Autowired
	private List<SmsSender> senders;

	@Autowired
	private HoldExpiryJob expiry;

	@BeforeEach
	void quietProvider() {
		sms.reset();
	}

	@Test
	void aCodOrderGetsOneReceivedNoticeOnTheAccountPhone() throws Exception {
		String customer = bearer("CUSTOMER");
		UUID orderId = placeOrder(customer, "COD", slot, line(fudge, 1, six));

		assertThat(dispatcher.dispatchDue()).isEqualTo(1);

		SmsMessage message = sms.sent().getFirst();
		assertThat(sms.sent()).hasSize(1);
		// The account holder, not the delivery contact (+8801712345678, possibly a gift recipient).
		assertThat(message.to()).isEqualTo(accountPhone(customer)).isNotEqualTo("+8801712345678");
		assertThat(message.text()).startsWith("Baked & Browned: order " + orderNumber(orderId) + " received.")
			.contains("Total BDT 1,310.00, cash on delivery")
			// Nothing the customer typed is echoed.
			.doesNotContain("Nusrat", "Ring twice", "House 12");
		Map<String, Object> notice = notice(orderId, "ORDER_RECEIVED");
		assertThat(notice).containsEntry("status", "SENT")
			.containsEntry("channel", "SMS")
			.containsEntry("provider", FakeSmsSender.PROVIDER)
			.containsEntry("attempts", 1)
			.containsEntry("recipient", message.to())
			.containsEntry("body", message.text())
			.containsEntry("user_id", userIdOf(customer));
		assertThat(notice.get("id")).isEqualTo(message.idempotencyKey());
		assertThat(dispatcher.pendingCount()).isZero();
	}

	@Test
	void aManualOrderIsAskedToPayThenToldItIsConfirmed() throws Exception {
		String customer = bearer("CUSTOMER");
		UUID orderId = placeOrder(customer, "MANUAL", slot, line(fudge, 1, six));
		dispatcher.dispatchDue();
		UUID paymentId = jdbc.queryForObject("SELECT id FROM payments WHERE order_id = ?", UUID.class, orderId);

		mockMvc.perform(post("/api/v1/admin/payments/{id}/verification", paymentId)
			.header(HttpHeaders.AUTHORIZATION, bearer("ADMIN"))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"decision\":\"APPROVE\",\"note\":\"Matched statement\"}")).andExpect(status().isOk());
		assertThat(dispatcher.dispatchDue()).isEqualTo(1);

		assertThat(sms.sent()).extracting(SmsMessage::text)
			.satisfiesExactly(first -> assertThat(first).contains("Please pay BDT 1,310.00 by"),
					second -> assertThat(second).contains("payment received. Order " + orderNumber(orderId)
							+ " is confirmed for delivery"));
	}

	@Test
	void cancellingAPaidOrderMentionsTheRefund() throws Exception {
		String customer = bearer("CUSTOMER");
		UUID orderId = placeOrder(customer, "ONLINE", slot, line(fudge, 1, six));
		jdbc.update("UPDATE payments SET status = 'SUCCEEDED' WHERE order_id = ?", orderId);
		jdbc.update("UPDATE orders SET status = 'CONFIRMED' WHERE id = ?", orderId);
		cancel(customer, orderId, null).andExpect(status().isOk());

		assertThat(dispatcher.dispatchDue()).isEqualTo(2);

		assertThat(notice(orderId, "ORDER_CANCELLED").get("body")).isEqualTo("Baked & Browned: order "
				+ orderNumber(orderId) + " has been cancelled. Your refund of BDT 1,310.00 is being processed.");
		// Sent after payment, the received notice does not ask for it.
		assertThat((String) notice(orderId, "ORDER_RECEIVED").get("body")).doesNotContain("Please pay");
	}

	@Test
	void anExpiredOrderIsToldWhy() throws Exception {
		UUID orderId = placeOrder(bearer("CUSTOMER"), "ONLINE", slot, line(fudge, 1, six));
		jdbc.update("UPDATE orders SET expires_at = now() - interval '1 minute' WHERE id = ?", orderId);
		assertThat(expiry.expireDue()).isEqualTo(1);

		assertThat(dispatcher.dispatchDue()).isEqualTo(2);

		assertThat(notice(orderId, "ORDER_EXPIRED").get("body")).isEqualTo("Baked & Browned: order "
				+ orderNumber(orderId) + " was cancelled because payment was not completed in time.");
	}

	@Test
	void aRedeliveredEventIsNotSentTwice() throws Exception {
		UUID orderId = placeOrder(bearer("CUSTOMER"), "COD", slot, line(fudge, 1, six));
		OutboxMessage event = event(orderId, OrderEvents.PLACED);
		OutboxHandler handler = handler(OrderEvents.PLACED);

		// At-least-once delivery: the same event arrives again (lease expired, process restarted, ...).
		handler.handle(event);
		handler.handle(event);
		handler.handle(new OutboxMessage(event.id(), event.aggregateType(), event.aggregateId(), event.eventType(),
				event.payload(), 3));

		assertThat(sms.sent()).hasSize(1);
		assertThat(count("notifications")).isEqualTo(1);
	}

	@Test
	void aProviderFailureIsRetriedWithTheSameTextAndNoDuplicate() throws Exception {
		UUID orderId = placeOrder(bearer("CUSTOMER"), "COD", slot, line(fudge, 1, six));
		sms.failNext(1);

		assertThat(dispatcher.dispatchDue()).isZero();
		assertThat(sms.sent()).isEmpty();
		assertThat(notice(orderId, "ORDER_RECEIVED")).containsEntry("status", "PENDING").containsEntry("attempts", 1);
		assertThat(jdbc.queryForObject("SELECT last_error FROM outbox_events WHERE aggregate_id = ?", String.class,
				orderId)).contains("SmsDeliveryException");
		// The order itself is untouched by the failed notice.
		assertThat(orderStatus(orderId)).isEqualTo("CONFIRMED");
		String body = (String) notice(orderId, "ORDER_RECEIVED").get("body");

		jdbc.update("UPDATE outbox_events SET next_attempt_at = now() - interval '1 second'");
		assertThat(dispatcher.dispatchDue()).isEqualTo(1);

		assertThat(sms.sent()).singleElement().extracting(SmsMessage::text).isEqualTo(body);
		assertThat(notice(orderId, "ORDER_RECEIVED")).containsEntry("status", "SENT").containsEntry("attempts", 2);
	}

	@Test
	void aPermanentRejectionEndsTheNoticeWithoutRetrying() throws Exception {
		UUID orderId = placeOrder(bearer("CUSTOMER"), "COD", slot, line(fudge, 1, six));
		sms.rejectNext(1);

		// The event completes: nothing is left to retry.
		assertThat(dispatcher.dispatchDue()).isEqualTo(1);
		assertThat(dispatcher.pendingCount()).isZero();
		assertThat(sms.sent()).isEmpty();
		assertThat(outcome(orderId, "ORDER_RECEIVED")).containsEntry("status", "FAILED")
			.containsEntry("failure_code", FakeSmsSender.REJECTED_CODE)
			.containsEntry("provider", FakeSmsSender.PROVIDER)
			.containsEntry("attempts", 1)
			.containsEntry("ended", true);
		assertThat(orderStatus(orderId)).isEqualTo("CONFIRMED");

		// A redelivered event leaves it alone.
		handler(OrderEvents.PLACED).handle(event(orderId, OrderEvents.PLACED));
		assertThat(sms.sent()).isEmpty();
		assertThat(outcome(orderId, "ORDER_RECEIVED")).containsEntry("status", "FAILED").containsEntry("attempts", 1);
	}

	@Test
	void aNoticeStillUnsentADayAfterItsEventExpires() throws Exception {
		UUID orderId = placeOrder(bearer("CUSTOMER"), "COD", slot, line(fudge, 1, six));
		sms.failNext(1);
		assertThat(dispatcher.dispatchDue()).isZero();
		assertThat(outcome(orderId, "ORDER_RECEIVED")).containsEntry("status", "PENDING");

		// The provider kept failing; the next retry comes 24 hours after the event.
		jdbc.update("UPDATE outbox_events SET created_at = now() - interval '24 hours 1 minute',"
				+ " next_attempt_at = now() - interval '1 second' WHERE aggregate_id = ?", orderId);
		assertThat(dispatcher.dispatchDue()).isEqualTo(1);

		assertThat(sms.sent()).isEmpty();
		assertThat(outcome(orderId, "ORDER_RECEIVED")).containsEntry("status", "EXPIRED")
			.containsEntry("failure_code", "EXPIRED")
			.containsEntry("attempts", 1)
			.containsEntry("ended", true);
		assertThat(dispatcher.pendingCount()).isZero();
	}

	@Test
	void aStalledOutboxDoesNotResetTheClock() throws Exception {
		UUID orderId = placeOrder(bearer("CUSTOMER"), "COD", slot, line(fudge, 1, six));
		// Never attempted until a day later (for example the dispatcher was down).
		jdbc.update("UPDATE outbox_events SET created_at = now() - interval '25 hours' WHERE aggregate_id = ?", orderId);

		assertThat(dispatcher.dispatchDue()).isEqualTo(1);

		assertThat(sms.sent()).isEmpty();
		assertThat(outcome(orderId, "ORDER_RECEIVED")).containsEntry("status", "EXPIRED").containsEntry("attempts", 0);
	}

	@Test
	void aNoticeJustUnderADayOldIsStillSent() throws Exception {
		UUID orderId = placeOrder(bearer("CUSTOMER"), "COD", slot, line(fudge, 1, six));
		jdbc.update("UPDATE outbox_events SET created_at = now() - interval '23 hours 59 minutes' WHERE aggregate_id = ?",
				orderId);

		assertThat(dispatcher.dispatchDue()).isEqualTo(1);

		assertThat(sms.sent()).hasSize(1);
		assertThat(outcome(orderId, "ORDER_RECEIVED")).containsEntry("status", "SENT").containsEntry("ended", false);
	}

	@Test
	void everyOrderEventHasExactlyOneHandler() {
		assertThat(handlers).extracting(OutboxHandler::eventType)
			.contains(OrderEvents.PLACED, OrderEvents.CONFIRMED, OrderEvents.CANCELLED, OrderEvents.EXPIRED)
			.doesNotHaveDuplicates();
	}

	@Test
	void theTestProfileWiresOnlyTheFakeSender() {
		assertThat(senders).singleElement().isInstanceOf(FakeSmsSender.class);

		ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(FakeSmsSender.class);
		runner.withPropertyValues("spring.profiles.active=prod")
			.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(SmsSender.class));
		runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(SmsSender.class));
		runner.withPropertyValues("spring.profiles.active=dev")
			.run(context -> assertThat(context).hasSingleBean(SmsSender.class));
		runner.withPropertyValues("spring.profiles.active=dev,prod").run(context -> assertThat(context).hasFailed());
	}

	@Test
	void logsAndToStringNeverShowTheTextOrFullNumber() {
		SmsMessage message = new SmsMessage(UUID.randomUUID(), "+8801912345678", "Secret text");

		assertThat(message.toString()).contains("+88019******78").doesNotContain("Secret", "12345");
	}

	// ------------------------------------------------------------------ helpers

	private String accountPhone(String bearer) {
		return jdbc.queryForObject("SELECT phone FROM users WHERE id = ?", String.class, userIdOf(bearer));
	}

	private String orderNumber(UUID orderId) {
		return jdbc.queryForObject("SELECT order_number FROM orders WHERE id = ?", String.class, orderId);
	}

	private Map<String, Object> notice(UUID orderId, String kind) {
		return jdbc.queryForMap("""
				SELECT id, status::text AS status, channel::text AS channel, provider, attempts, recipient, body, user_id
				FROM notifications WHERE order_id = ? AND kind = ?""", orderId, kind);
	}

	private Map<String, Object> outcome(UUID orderId, String kind) {
		return jdbc.queryForMap("""
				SELECT status::text AS status, failure_code, provider, attempts, failed_at IS NOT NULL AS ended
				FROM notifications WHERE order_id = ? AND kind = ?""", orderId, kind);
	}

	private OutboxMessage event(UUID orderId, String type) {
		return jdbc.queryForObject("""
				SELECT id, aggregate_type, aggregate_id, event_type, payload::text AS payload FROM outbox_events
				WHERE aggregate_id = ? AND event_type = ?""",
				(rs, row) -> new OutboxMessage(rs.getObject("id", UUID.class), rs.getString("aggregate_type"),
						rs.getObject("aggregate_id", UUID.class), rs.getString("event_type"), rs.getString("payload"), 1),
				orderId, type);
	}

	private OutboxHandler handler(String type) {
		return handlers.stream().filter(h -> h.eventType().equals(type)).findFirst().orElseThrow();
	}

}
