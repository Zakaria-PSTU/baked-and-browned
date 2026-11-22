package com.bakedandbrowned.common.readiness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

import com.bakedandbrowned.BakedAndBrownedApplication;
import com.bakedandbrowned.IntegrationTest;

/**
 * QA finding F-3 (ADR-0030): under {@code prod}, the readiness check refuses an unsafe
 * configuration before the application opens a database connection or runs a migration. Each
 * test starts the real application, as a deployment would, against its own brand-new database in
 * the test container (or against no database at all).
 */
@IntegrationTest
class ProductionStartupOrderIntegrationTests {

	private static final String NOT_READY = "Production configuration is not ready";

	@Autowired
	private JdbcConnectionDetails database;

	@Autowired
	private JdbcTemplate jdbc;

	@TempDir
	Path proofs;

	private String probe;

	@AfterEach
	void dropProbeDatabase() {
		if (probe != null) {
			jdbc.execute("DROP DATABASE IF EXISTS " + probe + " WITH (FORCE)");
		}
	}

	@Test
	void anUnsafeConfigurationIsRefusedWithoutTryingTheDatabase() throws IOException {
		// Nothing listens on this port: had startup reached for the database, the failure would be
		// a connection error instead of the readiness refusal.
		int closedPort;
		try (ServerSocket socket = new ServerSocket(0)) {
			closedPort = socket.getLocalPort();
		}
		Map<String, Object> unsafe = unsafe();
		unsafe.put("spring.datasource.url", "jdbc:postgresql://localhost:" + closedPort + "/nothing_here");

		assertThatThrownBy(() -> start(unsafe).close())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining(NOT_READY);
	}

	@Test
	void anUnsafeConfigurationLeavesAFreshDatabaseUntouched() throws SQLException {
		String url = newProbeDatabase();
		Map<String, Object> unsafe = unsafe();
		unsafe.put("spring.datasource.url", url);

		assertThatThrownBy(() -> start(unsafe).close()).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining(NOT_READY);

		// Not one migration ran: no tables at all, not even Flyway's history.
		assertThat(tables(url)).isZero();
	}

	@Test
	void aSafeConfigurationStartsAndMigratesAsBefore() throws Exception {
		String url = newProbeDatabase();
		Map<String, Object> safe = safe();
		safe.put("spring.datasource.url", url);

		try (ConfigurableApplicationContext context = start(safe)) {
			assertThat(context.isActive()).isTrue();
			assertThat(context.getBean(ProductionReadinessCheck.class)).isNotNull();
		}
		try (Connection connection = connect(url); ResultSet rs = connection.createStatement()
			.executeQuery("SELECT count(*) FROM flyway_schema_history WHERE success AND version IS NOT NULL")) {
			rs.next();
			assertThat(rs.getInt(1)).as("versioned migrations applied").isGreaterThanOrEqualTo(16);
		}
	}

	/**
	 * The refusals that used to come from ordinary beans (JWT key, SMS sender, fake adapters) now come
	 * from the readiness check too: the safe configuration with exactly one thing broken. The closed
	 * port proves the database was not tried (a connection error would replace the refusal).
	 */
	@ParameterizedTest(name = "{0}")
	@CsvSource({ "no JWT key,app.security.jwt.private-key", "unparseable JWT key,app.security.jwt.private-key",
			"SMS sender not enabled,app.notifications.sms.ssl-wireless.enabled",
			"SMS token missing,app.notifications.sms.ssl-wireless.api-token",
			"SMS sender id missing,app.notifications.sms.ssl-wireless.sender-id",
			"prod combined with dev,spring.profiles.active" })
	void aLateRefusalNowHappensWithoutTryingTheDatabase(String broken, String setting) throws Exception {
		int closedPort;
		try (ServerSocket socket = new ServerSocket(0)) {
			closedPort = socket.getLocalPort();
		}
		Map<String, Object> env = breakOne(broken);
		env.put("spring.datasource.url", "jdbc:postgresql://localhost:" + closedPort + "/nothing_here");

		assertThatThrownBy(() -> start(env).close()).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining(NOT_READY)
			.hasMessageContaining(setting)
			.message()
			.doesNotContain(SECRET_TOKEN, SECRET_KEY_TEXT);
	}

	/** The same refusals leave a fresh database with no tables: no migration ran. */
	@ParameterizedTest(name = "{0}")
	@CsvSource({ "no JWT key,app.security.jwt.private-key", "unparseable JWT key,app.security.jwt.private-key",
			"SMS sender not enabled,app.notifications.sms.ssl-wireless.enabled",
			"SMS token missing,app.notifications.sms.ssl-wireless.api-token",
			"SMS sender id missing,app.notifications.sms.ssl-wireless.sender-id",
			"prod combined with dev,spring.profiles.active" })
	void aLateRefusalNowLeavesAFreshDatabaseUntouched(String broken, String setting) throws Exception {
		String url = newProbeDatabase();
		Map<String, Object> env = breakOne(broken);
		env.put("spring.datasource.url", url);

		assertThatThrownBy(() -> start(env).close()).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining(NOT_READY)
			.hasMessageContaining(setting);

		assertThat(tables(url)).isZero();
	}

	// ------------------------------------------------------------------ helpers
	private static final String SECRET_TOKEN = "test-token-not-real";

	private static final String SECRET_KEY_TEXT = "NOTAKEYNOTAKEY";

	/** The safe configuration with exactly one thing wrong. */
	private Map<String, Object> breakOne(String broken) throws Exception {
		Map<String, Object> env = safe();
		switch (broken) {
			case "no JWT key" -> env.remove("app.security.jwt.private-key");
			case "unparseable JWT key" -> env.put("app.security.jwt.private-key",
					"-----BEGIN PRIVATE KEY-----\n" + SECRET_KEY_TEXT + "\n-----END PRIVATE KEY-----\n");
			case "SMS sender not enabled" -> env.put("app.notifications.sms.ssl-wireless.enabled", "false");
			case "SMS token missing" -> env.put("app.notifications.sms.ssl-wireless.api-token", "");
			case "SMS sender id missing" -> env.put("app.notifications.sms.ssl-wireless.sender-id", " ");
			case "prod combined with dev" -> env.put("spring.profiles.active", "prod,dev");
			default -> throw new IllegalArgumentException(broken);
		}
		return env;
	}


	/** The shipped local-development defaults, which production must refuse. */
	private Map<String, Object> unsafe() {
		Map<String, Object> env = common();
		env.put("app.security.jwt.allow-ephemeral-key", "true");
		return env;
	}

	/** A production configuration that passes every readiness rule; no provider is reachable. */
	private Map<String, Object> safe() throws Exception {
		Map<String, Object> env = common();
		env.put("app.security.jwt.private-key", pkcs8Pem());
		env.put("app.security.allowed-origins", "https://shop.example.com");
		env.put("app.payments.online.return-url", "https://shop.example.com/orders/{orderId}/payment");
		env.put("app.payments.online.api-base-url", "https://api.example.com");
		env.put("app.payments.proof-storage.directory", proofs.toAbsolutePath().toString());
		return env;
	}

	/**
	 * What both share. An SMS sender is required under prod, so both have one, and the readiness
	 * rules are the only difference; .invalid never resolves, and nothing is sent at startup.
	 */
	private Map<String, Object> common() {
		Map<String, Object> env = new HashMap<>();
		env.put("spring.profiles.active", "prod");
		env.put("app.notifications.sms.ssl-wireless.enabled", "true");
		env.put("app.notifications.sms.ssl-wireless.base-url", "https://sms.invalid/api/v3");
		env.put("app.notifications.sms.ssl-wireless.api-token", "test-token-not-real");
		env.put("app.notifications.sms.ssl-wireless.sender-id", "TESTSENDER");
		env.put("spring.datasource.username", database.getUsername());
		env.put("spring.datasource.password", database.getPassword());
		env.put("server.port", "0");
		env.put("app.scheduling.enabled", "false");
		return env;
	}

	/** The real application with {@code variables} as its process environment, as production supplies it. */
	private static ConfigurableApplicationContext start(Map<String, Object> variables) {
		StandardEnvironment env = new StandardEnvironment();
		env.getPropertySources()
			.replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
					new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
							variables));
		return new SpringApplicationBuilder(BakedAndBrownedApplication.class).environment(env).run();
	}

	/** A brand-new, empty database in the test container; dropped after the test. */
	private String newProbeDatabase() {
		probe = "f3_probe_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		jdbc.execute("CREATE DATABASE " + probe);
		String url = database.getJdbcUrl();
		int query = url.indexOf('?');
		String base = query < 0 ? url : url.substring(0, query);
		return base.substring(0, base.lastIndexOf('/') + 1) + probe + (query < 0 ? "" : url.substring(query));
	}

	private Connection connect(String url) throws SQLException {
		return DriverManager.getConnection(url, database.getUsername(), database.getPassword());
	}

	private int tables(String url) throws SQLException {
		try (Connection connection = connect(url); ResultSet rs = connection.createStatement()
			.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'")) {
			rs.next();
			return rs.getInt(1);
		}
	}

	private static String pkcs8Pem() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		String body = Base64.getMimeEncoder(64, "\n".getBytes())
			.encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
		return "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n";
	}

}
