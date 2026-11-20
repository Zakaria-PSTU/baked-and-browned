package com.bakedandbrowned.notification.sms;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Development and test stand-in for an SMS provider: keeps sent messages in memory and logs only a
 * masked number. It refuses to start under the {@code prod} profile, and steps aside when the real
 * adapter is enabled (ADR-0027), so exactly one SmsSender exists. Tests can make the next sends fail
 * with {@link #failNext} (retried) or {@link #rejectNext} (permanent).
 */
@Component
@Profile({ "dev", "test" })
@ConditionalOnProperty(prefix = "app.notifications.sms.ssl-wireless", name = "enabled", havingValue = "false",
		matchIfMissing = true)
public class FakeSmsSender implements SmsSender {

	public static final String PROVIDER = "fake";

	/** The code a {@link #rejectNext} rejection carries. */
	public static final String REJECTED_CODE = "FAKE_INVALID_NUMBER";

	private static final Logger log = LoggerFactory.getLogger(FakeSmsSender.class);

	private final List<SmsMessage> sent = new ArrayList<>();

	private final AtomicInteger failures = new AtomicInteger();

	private final AtomicInteger rejections = new AtomicInteger();

	public FakeSmsSender(Environment environment) {
		if (environment.matchesProfiles("prod")) {
			throw new IllegalStateException("FakeSmsSender must never run with the prod profile");
		}
	}

	@Override
	public String provider() {
		return PROVIDER;
	}

	@Override
	public String send(SmsMessage message) throws SmsDeliveryException {
		if (rejections.getAndUpdate(n -> Math.max(n - 1, 0)) > 0) {
			throw new SmsRejectedException(REJECTED_CODE, "Fake SMS provider rejected " + message);
		}
		if (failures.getAndUpdate(n -> Math.max(n - 1, 0)) > 0) {
			throw new SmsDeliveryException("Fake SMS provider unavailable");
		}
		synchronized (sent) {
			sent.add(message);
		}
		log.info("Fake SMS {}", message);
		return "fake-" + UUID.randomUUID();
	}

	/** Messages sent so far, oldest first. */
	public List<SmsMessage> sent() {
		synchronized (sent) {
			return List.copyOf(sent);
		}
	}

	/** The next {@code count} sends fail with {@link SmsDeliveryException}. */
	public void failNext(int count) {
		failures.set(count);
	}

	/** The next {@code count} sends are rejected permanently with {@link #REJECTED_CODE}. */
	public void rejectNext(int count) {
		rejections.set(count);
	}

	public void reset() {
		synchronized (sent) {
			sent.clear();
		}
		failures.set(0);
		rejections.set(0);
	}

}
