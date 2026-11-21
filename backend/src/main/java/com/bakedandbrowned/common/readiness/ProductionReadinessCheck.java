package com.bakedandbrowned.common.readiness;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start the production web application while a local-development default is still in
 * place (ADR-0030). Each of these would otherwise start silently and fail only in front of
 * customers: refresh refused for the real site's origin, browsers sent to localhost after paying,
 * payment proofs written to the working directory, cookies without {@code Secure}, login tokens
 * signed with a key that dies on restart, or online payments taken by the SSLCommerz sandbox.
 * <p>
 * Every problem is reported in one message that names the setting, never its value. Active under
 * {@code prod}, except for the operator command ({@code provision-admin}), which serves no web
 * traffic. Cash on delivery as the only payment method is a business choice, so it is logged as a
 * warning, not refused.
 */
@Component
@Profile("prod & !provision-admin")
public class ProductionReadinessCheck {

	private static final Logger log = LoggerFactory.getLogger(ProductionReadinessCheck.class);

	private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1", "0.0.0.0");

	public ProductionReadinessCheck(Environment environment) {
		List<String> problems = problems(environment);
		if (!problems.isEmpty()) {
			throw new IllegalStateException("Production configuration is not ready (ADR-0030):\n - "
					+ String.join("\n - ", problems) + "\nSee docs/launch-readiness.md.");
		}
		for (String warning : warnings(environment)) {
			log.warn(warning);
		}
	}

	/** What must be fixed before production starts; empty when ready. Names settings, never values. */
	public static List<String> problems(Environment environment) {
		Binder binder = Binder.get(environment);
		List<String> problems = new ArrayList<>();

		List<String> origins = binder.bind("app.security.allowed-origins", Bindable.listOf(String.class))
			.orElse(List.of());
		if (origins.isEmpty() || !origins.stream().allMatch(ProductionReadinessCheck::isPublicHttps)) {
			problems.add("app.security.allowed-origins (APP_SECURITY_ALLOWED_ORIGINS) must list only the "
					+ "production site's https:// origins, not localhost");
		}
		if (!binder.bind("app.security.secure-cookies", Boolean.class).orElse(true)) {
			problems.add("app.security.secure-cookies must not be false in production");
		}
		if (binder.bind("app.security.jwt.allow-ephemeral-key", Boolean.class).orElse(false)) {
			problems.add("app.security.jwt.allow-ephemeral-key must not be true in production; set "
					+ "APP_SECURITY_JWT_PRIVATE_KEY from the secret store");
		}
		String returnUrl = environment.getProperty("app.payments.online.return-url");
		if (!isPublicHttps(returnUrl == null ? null : returnUrl.replace("{orderId}", "order"))) {
			problems.add("app.payments.online.return-url (APP_PAYMENTS_ONLINE_RETURN_URL) must be an https:// "
					+ "address of the production site, not localhost");
		}
		if (!isPublicHttps(environment.getProperty("app.payments.online.api-base-url"))) {
			problems.add("app.payments.online.api-base-url (APP_PAYMENTS_ONLINE_API_BASE_URL) must be the API's "
					+ "public https:// origin, not localhost");
		}
		if (binder.bind("app.payments.sslcommerz.enabled", Boolean.class).orElse(false)
				&& isSandbox(environment.getProperty("app.payments.sslcommerz.base-url"))) {
			problems.add("app.payments.sslcommerz.base-url (APP_PAYMENTS_SSLCOMMERZ_BASE_URL) must be the live "
					+ "SSLCommerz address when the gateway is enabled; the default is the sandbox");
		}
		if (!isAbsolute(environment.getProperty("app.payments.proof-storage.directory"))) {
			problems.add("app.payments.proof-storage.directory (APP_PAYMENTS_PROOF_STORAGE_DIRECTORY) must be an "
					+ "absolute path on a private, backed-up volume");
		}
		return problems;
	}

	/** Allowed but worth a look before launch. */
	public static List<String> warnings(Environment environment) {
		Binder binder = Binder.get(environment);
		boolean manual = binder.bind("app.payments.manual.accounts[0].account-number", String.class).isBound();
		boolean online = binder.bind("app.payments.sslcommerz.enabled", Boolean.class).orElse(false);
		if (!manual && !online) {
			return List.of("No manual receiving accounts and no online gateway are configured: customers can pay "
					+ "only by cash on delivery (docs/launch-readiness.md)");
		}
		return List.of();
	}

	private static boolean isPublicHttps(@Nullable String value) {
		if (value == null || value.isBlank()) {
			return false;
		}
		try {
			URI uri = new URI(value.strip());
			String host = uri.getHost();
			return "https".equalsIgnoreCase(uri.getScheme()) && host != null
					&& !LOCAL_HOSTS.contains(host.toLowerCase(Locale.ROOT));
		}
		catch (URISyntaxException ex) {
			return false;
		}
	}

	/** The SSLCommerz sandbox (sandbox.sslcommerz.com), or no address at all: the shipped default. */
	private static boolean isSandbox(@Nullable String value) {
		if (value == null || value.isBlank()) {
			return true;
		}
		try {
			String host = new URI(value.strip()).getHost();
			return host == null || host.toLowerCase(Locale.ROOT).contains("sandbox");
		}
		catch (URISyntaxException ex) {
			return true;
		}
	}

	/** Absolute on this machine, or a POSIX absolute path as containers use (checked on any OS). */
	private static boolean isAbsolute(@Nullable String value) {
		if (value == null || value.isBlank()) {
			return false;
		}
		String path = value.strip();
		try {
			return path.startsWith("/") || Path.of(path).isAbsolute();
		}
		catch (InvalidPathException ex) {
			return false;
		}
	}

}
