package com.bakedandbrowned.payment.gateway;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

/**
 * A raw, untrusted provider callback exactly as received by the webhook endpoint. Adapters parse
 * the body themselves (JSON or form-encoded), because authenticity checks often depend on the
 * exact bytes.
 *
 * @param headers request headers; names are normalized to lower case
 */
public record ProviderCallback(String provider, @Nullable String contentType, Map<String, String> headers,
		String body) {

	public ProviderCallback {
		Objects.requireNonNull(provider, "provider");
		Objects.requireNonNull(body, "body");
		headers = headers == null ? Map.of()
				: headers.entrySet()
					.stream()
					.collect(Collectors.toUnmodifiableMap(e -> e.getKey().toLowerCase(Locale.ROOT), Map.Entry::getValue,
							(first, second) -> first));
	}

	public @Nullable String header(String name) {
		return headers.get(name.toLowerCase(Locale.ROOT));
	}

	/** Callback bodies can contain payment details: never print them. */
	@Override
	public String toString() {
		return "ProviderCallback[provider=" + provider + ", contentType=" + contentType + ", bodyLength=" + body.length()
				+ "]";
	}

}
