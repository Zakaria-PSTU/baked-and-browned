package com.bakedandbrowned.notification.sms;

import java.util.UUID;

/**
 * Port to an SMS provider. Adapters must not log message text or full phone numbers.
 * <p>
 * Delivery is at-least-once: after a crash between a successful send and recording it, the same
 * message is sent again with the same {@code idempotencyKey}. Adapters pass the key to providers
 * that support deduplication.
 */
public interface SmsSender {

	/** Short provider name recorded with each sent message, for example {@code fake}. */
	String provider();

	/**
	 * Sends one message.
	 * @return the provider's message id
	 * @throws SmsRejectedException if the provider will never accept it (the caller gives up)
	 * @throws SmsDeliveryException if the provider did not accept it this time (the caller retries)
	 */
	String send(SmsMessage message) throws SmsDeliveryException;

	/**
	 * @param to Bangladeshi mobile number in the form {@code +8801XXXXXXXXX}
	 * @param text plain text, never HTML
	 */
	record SmsMessage(UUID idempotencyKey, String to, String text) {

		/** Masked number and length only: the text and number are personal data. */
		@Override
		public String toString() {
			return "SmsMessage[key=" + idempotencyKey + ", to=" + masked(to) + ", length=" + text.length() + "]";
		}

		/** {@code +88017******78}. */
		public static String masked(String phone) {
			return phone.length() < 8 ? "***" : phone.substring(0, 6) + "*".repeat(phone.length() - 8)
					+ phone.substring(phone.length() - 2);
		}

	}

	/** The provider refused or could not be reached; retrying later may succeed. */
	class SmsDeliveryException extends Exception {

		private static final long serialVersionUID = 1L;

		public SmsDeliveryException(String message) {
			super(message);
		}

		public SmsDeliveryException(String message, Throwable cause) {
			super(message, cause);
		}

	}

	/**
	 * The provider will never accept this message (for example an invalid or blocked number): it is
	 * not retried (ADR-0027). Never thrown for a wrong configuration, which fixing must not lose.
	 */
	class SmsRejectedException extends SmsDeliveryException {

		private static final long serialVersionUID = 1L;

		private final String code;

		/** @param code the provider's status code, recorded with the notice; no text or number */
		public SmsRejectedException(String code, String message) {
			super(message);
			this.code = code;
		}

		public String code() {
			return code;
		}

	}

}
