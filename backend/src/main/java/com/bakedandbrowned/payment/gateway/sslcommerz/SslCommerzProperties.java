package com.bakedandbrowned.payment.gateway.sslcommerz;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.payments.sslcommerz.*}, read only when {@code enabled} is true (ADR-0012). Credentials
 * come only from the environment or a secret store ({@code APP_PAYMENTS_SSLCOMMERZ_STORE_ID},
 * {@code APP_PAYMENTS_SSLCOMMERZ_STORE_PASSWORD}); startup fails without them.
 *
 * @param baseUrl {@code https://sandbox.sslcommerz.com} or {@code https://securepay.sslcommerz.com};
 * plain http only for a local stub
 * @param ipnUrl the public webhook URL, {@code .../api/v1/payments/webhooks/sslcommerz}
 * @param fallbackCustomerEmail SSLCommerz requires a customer email; used when the account has none
 * @param customerCity, {@code customerPostcode}, {@code customerCountry}: sent as the customer's
 * address details, which SSLCommerz requires; deliveries are within Dhaka
 */
@ConfigurationProperties("app.payments.sslcommerz")
public record SslCommerzProperties(boolean enabled, @Nullable URI baseUrl, @Nullable String storeId,
		@Nullable String storePassword, @Nullable URI ipnUrl, @Nullable String fallbackCustomerEmail,
		@Nullable String customerCity, @Nullable String customerPostcode, @Nullable String customerCountry,
		@Nullable Duration connectTimeout, @Nullable Duration readTimeout) {

	/** Checks the settings the adapter needs; called only when it is enabled. */
	Settings settings() {
		URI base = required(baseUrl, "base-url");
		boolean local = "localhost".equals(base.getHost()) || "127.0.0.1".equals(base.getHost());
		if (!"https".equals(base.getScheme()) && !local) {
			throw new IllegalStateException("app.payments.sslcommerz.base-url must use https");
		}
		String email = required(fallbackCustomerEmail, "fallback-customer-email");
		if (!email.contains("@")) {
			throw new IllegalStateException("app.payments.sslcommerz.fallback-customer-email must be an email address");
		}
		return new Settings(stripSlash(base.toString()), required(storeId, "store-id"),
				required(storePassword, "store-password"), required(ipnUrl, "ipn-url"), email,
				required(customerCity, "customer-city"), required(customerPostcode, "customer-postcode"),
				required(customerCountry, "customer-country"), positive(connectTimeout, "connect-timeout"),
				positive(readTimeout, "read-timeout"));
	}

	private static <T> T required(@Nullable T value, String name) {
		if (value == null) {
			throw new IllegalStateException("app.payments.sslcommerz." + name + " is required when enabled");
		}
		return value;
	}

	private static String required(@Nullable String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalStateException("app.payments.sslcommerz." + name + " is required when enabled");
		}
		return value.strip();
	}

	private static Duration positive(@Nullable Duration value, String name) {
		Duration duration = required(value, name);
		if (duration.isNegative() || duration.isZero()) {
			throw new IllegalStateException("app.payments.sslcommerz." + name + " must be positive");
		}
		return duration;
	}

	private static String stripSlash(String url) {
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}

	/** Validated settings. Holds the store password: never print it. */
	record Settings(String baseUrl, String storeId, String storePassword, URI ipnUrl, String fallbackCustomerEmail,
			String customerCity, String customerPostcode, String customerCountry, Duration connectTimeout,
			Duration readTimeout) {

		Settings {
			Objects.requireNonNull(storePassword, "storePassword");
		}

		@Override
		public String toString() {
			return "SslCommerzSettings[baseUrl=" + baseUrl + ", storeId=" + storeId + "]";
		}

	}

}
