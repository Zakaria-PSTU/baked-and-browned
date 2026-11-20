package com.bakedandbrowned.schema;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Customer notices (V6, V14): one per outbox event and channel; SENT means it was handed over;
 * FAILED and EXPIRED record when and why; an ended notice never changes again.
 */
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
	void anUnsentNoticeEndsWithWhenAndWhy() {
		UUID customer = user();
		UUID orderId = order(customer, "COD", "CONFIRMED");
		jdbc.update(INSERT, UUID.randomUUID(), orderId, customer);

		assertRejectedBy("ck_notification_failure",
				() -> jdbc.update("UPDATE notifications SET status = 'FAILED', failed_at = now()"));
		assertRejectedBy("ck_notification_failure",
				() -> jdbc.update("UPDATE notifications SET failed_at = now(), failure_code = '4025'"));
		jdbc.update("UPDATE notifications SET status = 'FAILED', failed_at = now(), failure_code = '4025'");
	}

	@Test
	void anEndedNoticeNeverChangesAgain() {
		UUID customer = user();
		UUID orderId = order(customer, "COD", "CONFIRMED");
		jdbc.update(INSERT, UUID.randomUUID(), orderId, customer);
		jdbc.update("UPDATE notifications SET status = 'EXPIRED', failed_at = now(), failure_code = 'EXPIRED'");

		assertRejectedBy("ck_notification_final", () -> jdbc.update("""
				UPDATE notifications SET status = 'SENT', sent_at = now(), provider = 'fake', failed_at = NULL,
				failure_code = NULL"""));
		assertRejectedBy("ck_notification_final", () -> jdbc.update("UPDATE notifications SET attempts = attempts + 1"));

		jdbc.update(INSERT.replace("'ORDER_RECEIVED'", "'ORDER_CONFIRMED'"), UUID.randomUUID(), orderId, customer);
		jdbc.update("UPDATE notifications SET status = 'SENT', sent_at = now(), provider = 'fake' WHERE kind = 'ORDER_CONFIRMED'");
		assertRejectedBy("ck_notification_final",
				() -> jdbc.update("UPDATE notifications SET body = 'changed' WHERE kind = 'ORDER_CONFIRMED'"));
	}

	@Test
	void onlyKnownNoticeKinds() {
		UUID customer = user();
		UUID orderId = order(customer, "COD", "CONFIRMED");

		assertRejectedBy("notifications_kind_check", () -> jdbc
			.update(INSERT.replace("'ORDER_RECEIVED'", "'MARKETING'"), UUID.randomUUID(), orderId, customer));
	}

}
