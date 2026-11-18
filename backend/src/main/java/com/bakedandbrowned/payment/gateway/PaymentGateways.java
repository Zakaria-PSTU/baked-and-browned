package com.bakedandbrowned.payment.gateway;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The gateway adapters present in this deployment, and which one new ONLINE payments use.
 * <p>
 * {@code app.payments.online.provider} names it. Left blank, the only adapter present is used; with
 * none (for example the prod profile before an adapter is configured) online payments are
 * unavailable, and with several the property is required, so startup fails rather than guessing.
 * Callbacks are routed by provider key to the adapter that issued the session, whichever is active.
 */
@Component
public class PaymentGateways {

	private final Map<String, PaymentGateway> byProvider;

	private final @Nullable PaymentGateway active;

	public PaymentGateways(List<PaymentGateway> gateways,
			@Value("${app.payments.online.provider:}") @Nullable String configured) {
		this.byProvider = gateways.stream()
			.collect(Collectors.toUnmodifiableMap(PaymentGateway::provider, Function.identity()));
		if (configured != null && !configured.isBlank()) {
			PaymentGateway named = byProvider.get(configured.strip());
			if (named == null) {
				throw new IllegalStateException("app.payments.online.provider names an unknown gateway '"
						+ configured.strip() + "'; present: " + byProvider.keySet());
			}
			this.active = named;
		}
		else if (gateways.size() > 1) {
			throw new IllegalStateException(
					"Several payment gateways are present " + byProvider.keySet() + "; set app.payments.online.provider");
		}
		else {
			this.active = gateways.isEmpty() ? null : gateways.get(0);
		}
	}

	/** The gateway new ONLINE sessions are opened with, if online payments are available. */
	public Optional<PaymentGateway> active() {
		return Optional.ofNullable(active);
	}

	/** The adapter for {@code provider} (from a webhook path or a stored payment). */
	public Optional<PaymentGateway> byProvider(String provider) {
		return Optional.ofNullable(byProvider.get(provider));
	}

}
