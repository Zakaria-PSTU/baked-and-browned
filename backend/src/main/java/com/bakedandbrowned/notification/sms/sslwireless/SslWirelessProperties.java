package com.bakedandbrowned.notification.sms.sslwireless;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.notifications.sms.ssl-wireless.*}, read only when {@code enabled} is true (ADR-0027).
 * The API token comes only from the environment or a secret store
 * ({@code APP_NOTIFICATIONS_SMS_SSL_WIRELESS_API_TOKEN}); startup fails without it.
 *
 * @param baseUrl {@code https://smsplus.sslwireless.com/api/v3}; plain http only for a local stub
 * @param senderId the {@code sid} assigned to the account (a number at launch, a registered brand
 * name later)
 */
@ConfigurationProperties("app.notifications.sms.ssl-wireless")
public record SslWirelessProperties(boolean enabled, @Nullable URI baseUrl, @Nullable String apiToken,
		@Nullable String senderId, @Nullable Duration connectTimeout, @Nullable Duration readTimeout) {

	private static final String PREFIX = "app.notifications.sms.ssl-wireless.";

	/** Checks the settings the adapter needs; called only when it is enabled. */
	Settings settings() {
		URI base = required(baseUrl, "base-url");
		boolean local = "localhost".equals(base.getHost()) || "127.0.0.1".equals(base.getHost());
		if (!"https".equals(base.getScheme()) && !local) {
			throw new IllegalStateException(PREFIX + "base-url must use https");
		}
		String sid = required(senderId, "sender-id");
		if (sid.length() > 20) {
			// The documented maximum length of sid.
			throw new IllegalStateException(PREFIX + "sender-id must be at most 20 characters");
		}
		return new Settings(stripSlash(base.toString()), required(apiToken, "api-token"), sid,
				positive(connectTimeout, "connect-timeout"), positive(readTimeout, "read-timeout"));
	}

	/** The record's default would print the token. */
	@Override
	public String toString() {
		return "SslWirelessProperties[enabled=" + enabled + ", baseUrl=" + baseUrl + ", senderId=" + senderId
				+ ", apiToken=" + (apiToken == null ? "null" : "***") + "]";
	}

	private static <T> T required(@Nullable T value, String name) {
		if (value == null) {
			throw new IllegalStateException(PREFIX + name + " is required when enabled");
		}
		return value;
	}

	private static String required(@Nullable String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(PREFIX + name + " is required when enabled");
		}
		return value.strip();
	}

	private static Duration positive(@Nullable Duration value, String name) {
		Duration duration = required(value, name);
		if (duration.isNegative() || duration.isZero()) {
			throw new IllegalStateException(PREFIX + name + " must be positive");
		}
		return duration;
	}

	private static String stripSlash(String url) {
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}

	/** Validated settings. Holds the API token: never print it. */
	record Settings(String baseUrl, String apiToken, String senderId, Duration connectTimeout, Duration readTimeout) {

		Settings {
			Objects.requireNonNull(apiToken, "apiToken");
		}

		@Override
		public String toString() {
			return "Settings[baseUrl=" + baseUrl + ", senderId=" + senderId + ", apiToken=***]";
		}

	}

}
