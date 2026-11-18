package com.bakedandbrowned.schema;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Customer notices (V6): one per outbox event and channel, and SENT means it was handed over. */
class NotificationSchemaTests extends AbstractSchemaTest {

	private static final String INSERT = """
			INSERT INTO notifications (outbox_event_id, order_id, user_id, kind, channel, recipient, body)
			VALUES (?, ?, ?, 'ORDER_RECEIVED', 'SMS', '+8801912345678', 'Baked & Browned: order received.')""";

	@Test
	void oneNoticePerEventAndChannel() {
		UUID customer = user();
		UUID orderId = order(customer, "COD", "CONFIRMED");
		UUID eventId = UUID.randomUUID();
		jdbc.update(INSERT, eventId, orderId, customer);

		assertRejectedBy("uq_notification_event_channel", () -> jdbc.update(INSERT, eventId, orderId, customer));
		jdbc.update(INSERT.replace("'SMS'", "'EMAIL'"), eventId, orderId, customer);
	}

	@Test
	void aSentNoticeRecordsWhenAndThroughWhichProvider() {
		UUID customer = user();
		UUID orderId = order(customer, "COD", "CONFIRMED");
		UUID eventId = UUID.randomUUID();
		jdbc.update(INSERT, eventId, orderId, customer);

		assertRejectedBy("notifications_check", () -> jdbc.update("UPDATE notifications SET status = 'SENT'"));
		jdbc.update("UPDATE notifications SET status = 'SENT', sent_at = now(), provider = 'fake'");
	}

	@Test
	void onlyKnownNoticeKinds() {
		UUID customer = user();
		UUID orderId = order(customer, "COD", "CONFIRMED");

		assertRejectedBy("notifications_kind_check", () -> jdbc
			.update(INSERT.replace("'ORDER_RECEIVED'", "'MARKETING'"), UUID.randomUUID(), orderId, customer));
	}

}
