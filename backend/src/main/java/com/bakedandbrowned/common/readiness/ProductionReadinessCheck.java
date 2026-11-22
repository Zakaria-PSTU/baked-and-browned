package com.bakedandbrowned.common.readiness;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.bakedandbrowned.common.security.JwtSigningKey;

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
 * <p>
 * It is a {@link BeanFactoryPostProcessor} so that Spring runs the check before creating any
 * ordinary bean: before the web server, the DataSource, Flyway and everything that uses the
 * database. A refused configuration therefore opens no database connection and runs no migration
 * (QA finding F-3). It needs nothing but the {@link Environment}.
 * <p>
 * The refusals that used to come from ordinary beans are rules here too, so they also happen before
 * the database: no usable JWT signing key, no SMS sender (the real adapter is the only one in
 * {@code prod}), and {@code prod} combined with {@code dev} or {@code test}, the only way the fake
 * adapters could load. The rules only look at settings; the adapters still validate their own
 * details (for example the SMS address scheme) when they are created.
 */
@Component
@Profile("prod & !provision-admin")
public class ProductionReadinessCheck implements BeanFactoryPostProcessor, EnvironmentAware {

	private static final Logger log = LoggerFactory.getLogger(ProductionReadinessCheck.class);

	private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1", "0.0.0.0");

	/** Set by Spring before {@link #postProcessBeanFactory}; post-processors get no constructor injection. */
	private @Nullable Environment environment;

	@Override
	public void setEnvironment(Environment environment) {
		this.environment = environment;
	}

	/** Runs the check; called before any ordinary bean is created, so before any database access. */
	@Override
	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
		Environment env = environment != null ? environment : beanFactory.getBean(Environment.class);
		List<String> problems = problems(env);
		if (!problems.isEmpty()) {
			throw new IllegalStateException("Production configuration is not ready (ADR-0030):\n - "
					+ String.join("\n - ", problems) + "\nSee docs/launch-readiness.md.");
		}
		for (String warning : warnings(env)) {
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
		if (!isUsableSigningKey(environment.getProperty("app.security.jwt.private-key"))) {
			problems.add("app.security.jwt.private-key (APP_SECURITY_JWT_PRIVATE_KEY) must be set to a PKCS#8 PEM "
					+ "RSA key of 2048 bits or more, from the secret store");
		}
		smsProblems(binder, environment, problems);
		List<String> profiles = Arrays.asList(environment.getActiveProfiles());
		if (profiles.contains("dev") || profiles.contains("test")) {
			problems.add("spring.profiles.active (SPRING_PROFILES_ACTIVE) must not combine prod with dev or test: "
					+ "those profiles load the fake SMS sender and the fake payment gateway");
		}
		return problems;
	}

	/**
	 * In {@code prod} the only SmsSender is the SSL Wireless adapter, so it must be enabled and have
	 * its token and sender id. Only presence is checked; the adapter validates the rest when created.
	 */
	private static void smsProblems(Binder binder, Environment environment, List<String> problems) {
		if (!binder.bind("app.notifications.sms.ssl-wireless.enabled", Boolean.class).orElse(false)) {
			problems.add("app.notifications.sms.ssl-wireless.enabled (APP_NOTIFICATIONS_SMS_SSL_WIRELESS_ENABLED) "
					+ "must be true: production has no other SMS sender");
			return;
		}
		if (isBlank(environment.getProperty("app.notifications.sms.ssl-wireless.api-token"))) {
			problems.add("app.notifications.sms.ssl-wireless.api-token (APP_NOTIFICATIONS_SMS_SSL_WIRELESS_API_TOKEN) "
					+ "must be set from the secret store");
		}
		if (isBlank(environment.getProperty("app.notifications.sms.ssl-wireless.sender-id"))) {
			problems.add("app.notifications.sms.ssl-wireless.sender-id (APP_NOTIFICATIONS_SMS_SSL_WIRELESS_SENDER_ID) "
					+ "must be set to the sender id assigned to the account");
		}
	}

	/** Parses the key the way startup would, and throws the result away. Never reports why it failed. */
	private static boolean isUsableSigningKey(@Nullable String pem) {
		if (isBlank(pem)) {
			return false;
		}
		try {
			JwtSigningKey.fromPem("readiness-check", pem);
			return true;
		}
		catch (RuntimeException ex) {
			// Deliberately not logged or chained: the message could echo part of the key.
			return false;
		}
	}

	private static boolean isBlank(@Nullable String value) {
		return value == null || value.isBlank();
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
