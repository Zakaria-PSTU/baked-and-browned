package com.bakedandbrowned.payment.gateway.fake;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.bakedandbrowned.common.value.Money;
import com.bakedandbrowned.payment.gateway.GatewayPaymentStatus;
import com.bakedandbrowned.payment.gateway.HostedPaymentSession;
import com.bakedandbrowned.payment.gateway.PaymentGateway;
import com.bakedandbrowned.payment.gateway.PaymentSessionRequest;
import com.bakedandbrowned.payment.gateway.PaymentVerificationException;
import com.bakedandbrowned.payment.gateway.ProviderCallback;
import com.bakedandbrowned.payment.gateway.VerifiedPaymentResult;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * In-memory stand-in for a hosted gateway, for the {@code dev} and {@code test} profiles only
 * (ADR-0012); it refuses to start if {@code prod} is also active. It behaves like a real provider in
 * the ways that matter to callers:
 * <ul>
 * <li>The provider side is simulated with {@link #settle}: the "customer" pays or fails on the hosted
 * page.</li>
 * <li>Callbacks are JSON {@code {"sessionId": ..., "status": ...}} signed with an HMAC-SHA256 in
 * {@value #SIGNATURE_HEADER} under a per-process secret ({@link #callbackFor} builds one).</li>
 * <li>{@link #verify} checks the signature, then resolves the session through its own record (the
 * stand-in for a server-to-server validation call). The status claimed in the callback is never
 * trusted.</li>
 * </ul>
 */
@Component
@Profile({ "dev", "test" })
public class FakePaymentGateway implements PaymentGateway {

	public static final String PROVIDER = "fake";

	public static final String SIGNATURE_HEADER = "X-Fake-Signature";

	private final Map<String, Session> sessions = new ConcurrentHashMap<>();

	private final byte[] secret = new byte[32];

	private final URI checkoutBaseUrl;

	private final JsonMapper json;

	public FakePaymentGateway(Environment environment, JsonMapper json,
			@Value("${app.payments.fake.checkout-base-url}") URI checkoutBaseUrl) {
		if (environment.matchesProfiles("prod")) {
			throw new IllegalStateException("FakePaymentGateway must never run with the prod profile");
		}
		this.json = json;
		this.checkoutBaseUrl = checkoutBaseUrl;
		new SecureRandom().nextBytes(secret);
	}

	@Override
	public String provider() {
		return PROVIDER;
	}

	@Override
	public HostedPaymentSession createSession(PaymentSessionRequest request) {
		String sessionId = "fake_sess_" + UUID.randomUUID().toString().replace("-", "");
		sessions.put(sessionId, new Session(request.orderReference(), request.amount(), GatewayPaymentStatus.PENDING, null));
		return new HostedPaymentSession(sessionId, URI.create(checkoutBaseUrl + "?session=" + sessionId));
	}

	@Override
	public VerifiedPaymentResult verify(ProviderCallback callback) {
		String signature = callback.header(SIGNATURE_HEADER);
		if (signature == null || !MessageDigest.isEqual(signature.getBytes(StandardCharsets.US_ASCII),
				sign(callback.body()).getBytes(StandardCharsets.US_ASCII))) {
			throw new PaymentVerificationException("Fake gateway callback signature is missing or invalid");
		}
		String sessionId;
		try {
			JsonNode body = json.readTree(callback.body());
			JsonNode id = body.get("sessionId");
			if (id == null || !id.isString()) {
				throw new PaymentVerificationException("Fake gateway callback has no sessionId");
			}
			sessionId = id.asString();
		}
		catch (JacksonException ex) {
			throw new PaymentVerificationException("Fake gateway callback is not valid JSON", ex);
		}
		// Server-to-server check: the provider's record, not the callback's claim, is authoritative.
		return queryStatus(sessionId);
	}

	@Override
	public VerifiedPaymentResult queryStatus(String sessionId) {
		Session session = sessions.get(sessionId);
		if (session == null) {
			throw new PaymentVerificationException("Unknown fake gateway session");
		}
		return new VerifiedPaymentResult(PROVIDER, sessionId + ":" + session.status(), sessionId,
				session.transactionId(), session.orderReference(), session.amount(), "BDT", session.status());
	}

	// ------------------------------------------------------------------ provider-side simulation

	/** The customer finishes on the hosted page with {@code outcome}. */
	public void settle(String sessionId, GatewayPaymentStatus outcome) {
		sessions.computeIfPresent(sessionId, (id, session) -> new Session(session.orderReference(), session.amount(),
				outcome, outcome == GatewayPaymentStatus.SUCCEEDED ? "fake_txn_" + id.substring("fake_sess_".length()) : null));
	}

	/** The signed callback the provider would send, claiming {@code claimedStatus}. */
	public ProviderCallback callbackFor(String sessionId, GatewayPaymentStatus claimedStatus) {
		String body = json.writeValueAsString(Map.of("sessionId", sessionId, "status", claimedStatus.name()));
		return new ProviderCallback(PROVIDER, "application/json", Map.of(SIGNATURE_HEADER, sign(body)), body);
	}

	private String sign(String body) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret, "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("HmacSHA256 is unavailable", ex);
		}
	}

	private record Session(String orderReference, Money amount, GatewayPaymentStatus status,
			@Nullable String transactionId) {
	}

}
