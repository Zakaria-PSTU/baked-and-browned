package com.bakedandbrowned.notification.sms;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.bakedandbrowned.notification.sms.SmsSender.SmsMessage;

/**
 * What every SmsSender must do (ADR-0016, ADR-0027): name its provider, return a message id for an
 * accepted message, and accept a resend of the same notice (at-least-once delivery) without failing.
 */
public abstract class SmsSenderContract {

	protected abstract SmsSender sender();

	private static SmsMessage message(UUID key) {
		return new SmsMessage(key, "+8801812345678", "Baked & Browned: order BB-20261120-000009 is confirmed.");
	}

	@Test
	void namesItsProvider() {
		assertThat(sender().provider()).isNotBlank().doesNotContainAnyWhitespaces().hasSizeLessThanOrEqualTo(60);
	}

	@Test
	void returnsAMessageIdForAnAcceptedMessage() throws Exception {
		assertThat(sender().send(message(UUID.randomUUID()))).isNotBlank().hasSizeLessThanOrEqualTo(150);
	}

	@Test
	void acceptsAResendOfTheSameNotice() throws Exception {
		UUID key = UUID.randomUUID();
		sender().send(message(key));

		assertThat(sender().send(message(key))).isNotBlank().hasSizeLessThanOrEqualTo(150);
	}

}
