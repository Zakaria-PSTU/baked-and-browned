package com.bakedandbrowned.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import com.bakedandbrowned.BakedAndBrownedApplication;
import com.bakedandbrowned.common.outbox.OutboxDispatcher;
import com.bakedandbrowned.identity.service.AdminProvisioningService;
import com.bakedandbrowned.identity.service.AdminProvisioningService.ProvisioningRefused;
import com.bakedandbrowned.notification.service.OrderNotificationDelivery;
import com.bakedandbrowned.notification.sms.FakeSmsSender;
import com.bakedandbrowned.notification.sms.SmsSender;

/**
 * Runs the real {@code provision-admin} command, as an operator would, against the isolated test
 * database with production-like configuration: only that profile (no {@code test} or {@code dev}),
 * no SMS provider, no fake SMS sender. Variables are supplied as the process-environment property
 * source, which is where production supplies them. The only non-production setting is the
 * ephemeral JWT key.
 */
@ExtendWith(OutputCaptureExtension.class)
class AdminProvisioningCommandIntegrationTests extends IdentityIntegrationTest {

	private static final String FIRST_PHONE = "+8801711000201";

	private static final String FIRST_PASSWORD = "first-admin-password-1";

	private static final String SECOND_PASSWORD = "second-admin-password-2";

	@Autowired
	private JdbcConnectionDetails database;

	@TempDir
	Path secrets;

	@Test
	void bothModesRunWithoutSmsWebServerSchedulingOrNotificationDelivery(CapturedOutput output) throws Exception {
		try (ConfigurableApplicationContext first = command(firstAdminVariables())) {
			assertCommandModeOnly(first);
		}
		// Additional mode with the passwords in secret files, as a mounted container secret would be.
		Path provisionerSecret = Files.writeString(secrets.resolve("provisioner"), FIRST_PASSWORD + "\n");
		Path adminSecret = Files.writeString(secrets.resolve("admin"), SECOND_PASSWORD + "\n");
		try (ConfigurableApplicationContext additional = command(Map.of("ADMIN_PROVISION_MODE", "additional",
				"PROVISIONER_IDENTIFIER", FIRST_PHONE, "PROVISIONER_PASSWORD_FILE", provisionerSecret.toString(),
				"ADMIN_FULL_NAME", "Second Admin", "ADMIN_PHONE", "+8801711000202", "ADMIN_PASSWORD_FILE",
				adminSecret.toString()))) {
			assertCommandModeOnly(additional);
		}

		assertThat(jdbc.queryForList("SELECT phone FROM users WHERE role = 'ADMIN' ORDER BY created_at", String.class))
			.containsExactly(FIRST_PHONE, "+8801711000202");
		assertThat(jdbc.queryForList("SELECT actor_type::text FROM audit_events WHERE action = ? ORDER BY created_at",
				String.class, AdminProvisioningService.PROVISIONED))
			.containsExactly("SYSTEM", "ADMIN");
		// The second admin signs in with the password read from the file (trailing newline removed).
		assertThat(login("+8801711000202", SECOND_PASSWORD).body().get("user").get("role").asString())
			.isEqualTo("ADMIN");
		assertThat(output.getAll()).contains("Provisioned an additional ADMIN account")
			.doesNotContain(FIRST_PASSWORD, SECOND_PASSWORD);
	}

	@Test
	void refusalsFailTheCommandWithoutRevealingAccountsOrCredentials(CapturedOutput output) {
		command(firstAdminVariables()).close();

		Throwable wrongPassword = refusal(FIRST_PHONE, "not-the-password-1");
		Throwable unknownAccount = refusal("+8801799999999", "not-the-password-1");
		// The same error for an unknown account and a wrong password: existence is not revealed.
		assertThat(wrongPassword).isInstanceOf(ProvisioningRefused.class);
		assertThat(unknownAccount).isInstanceOf(ProvisioningRefused.class).hasMessage(wrongPassword.getMessage());
		assertThatThrownBy(() -> command(Map.of("ADMIN_PROVISION_MODE", "promote", "ADMIN_FULL_NAME", "Second Admin",
				"ADMIN_PHONE", "+8801711000202", "ADMIN_PASSWORD", SECOND_PASSWORD)))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("ADMIN_PROVISION_MODE must be 'first' or 'additional'");

		assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE role = 'ADMIN'", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForList("SELECT reason FROM audit_events WHERE action = ? ORDER BY created_at",
				String.class, AdminProvisioningService.REFUSED))
			.containsExactly(AdminProvisioningService.PROVISIONER_BAD_PASSWORD,
					AdminProvisioningService.PROVISIONER_NOT_FOUND);
		assertThat(output.getAll()).doesNotContain("not-the-password-1", SECOND_PASSWORD, FIRST_PASSWORD);
	}

	@Test
	void passwordsOutsideTheProcessEnvironmentAreRefused(CapturedOutput output) {
		Map<String, String> variables = new HashMap<>(firstAdminVariables());
		variables.remove("ADMIN_PASSWORD");

		// --ADMIN_PASSWORD=… (process listing, shell history)
		assertThatThrownBy(() -> command(variables, Map.of(), "--ADMIN_PASSWORD=" + FIRST_PASSWORD))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("ADMIN_PASSWORD must not be set in a command-line argument; provide it as ADMIN_PASSWORD_FILE"
					+ " (a secret file) or in the process environment");
		// java -DPROVISIONER_PASSWORD=… (process listing)
		assertThatThrownBy(() -> command(variables, Map.of("PROVISIONER_PASSWORD", FIRST_PASSWORD)))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageStartingWith("PROVISIONER_PASSWORD must not be set in a JVM system property (-D)");
		// --spring.application.json={"ADMIN_PASSWORD": …}
		assertThatThrownBy(() -> command(variables, Map.of(),
				"--spring.application.json={\"ADMIN_PASSWORD\":\"" + FIRST_PASSWORD + "\"}"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageStartingWith("ADMIN_PASSWORD must not be set in the configuration source 'spring.application.json'");

		assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
		assertThat(output.getAll()).doesNotContain(FIRST_PASSWORD);
	}

	@Test
	void theCommandRefusesToRunWithAWebServer(CapturedOutput output) {
		Map<String, String> variables = new HashMap<>(firstAdminVariables());
		variables.put("spring.main.web-application-type", "servlet");

		assertThatThrownBy(() -> command(variables)).isInstanceOf(IllegalStateException.class)
			.hasMessageStartingWith("provision-admin must not run with a web server");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
		assertThat(output.getAll()).doesNotContain(FIRST_PASSWORD);
	}

	@Test
	void theJwtKeyPolicyIsStillEnforcedInCommandMode() {
		Map<String, String> variables = new HashMap<>(firstAdminVariables());
		variables.put("app.security.jwt.allow-ephemeral-key", "false");

		assertThatThrownBy(() -> command(variables)).rootCause().hasMessageContaining("No JWT signing key");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
	}

	@Test
	void normalProductionWebStartupStillRequiresAnSmsSender() {
		assertThatThrownBy(() -> application(Map.of(), WebApplicationType.SERVLET).run())
			.rootCause()
			.isInstanceOf(NoSuchBeanDefinitionException.class)
			.hasMessageContaining(SmsSender.class.getName());
	}

	// ------------------------------------------------------------------ helpers

	private static void assertCommandModeOnly(ConfigurableApplicationContext context) {
		assertThat(context).isNotInstanceOf(WebServerApplicationContext.class);
		assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("provision-admin");
		assertThat(context.getBeanNamesForType(SmsSender.class)).isEmpty();
		assertThat(context.getBeanNamesForType(FakeSmsSender.class)).isEmpty();
		assertThat(context.getBeanNamesForType(OrderNotificationDelivery.class)).isEmpty();
		assertThat(context.getBeanNamesForType(OutboxDispatcher.class)).isEmpty();
		assertThat(context.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class))
			.as("no @Scheduled jobs are registered")
			.isEmpty();
	}

	private static Map<String, String> firstAdminVariables() {
		return Map.of("ADMIN_FULL_NAME", "Store Admin", "ADMIN_PHONE", FIRST_PHONE, "ADMIN_PASSWORD", FIRST_PASSWORD);
	}

	private Throwable refusal(String provisionerIdentifier, String provisionerPassword) {
		Map<String, String> variables = Map.of("ADMIN_PROVISION_MODE", "additional", "PROVISIONER_IDENTIFIER",
				provisionerIdentifier, "PROVISIONER_PASSWORD", provisionerPassword, "ADMIN_FULL_NAME", "Second Admin",
				"ADMIN_PHONE", "+8801711000202", "ADMIN_PASSWORD", SECOND_PASSWORD);
		try {
			command(variables).close();
		}
		catch (RuntimeException ex) {
			return ex;
		}
		throw new AssertionError("the command should have been refused");
	}

	/** The command as an operator runs it, with {@code variables} as its process environment. */
	private ConfigurableApplicationContext command(Map<String, String> variables, String... args) {
		return command(variables, Map.of(), args);
	}

	/** As above, plus JVM system properties ({@code -D}) for this run only. */
	private ConfigurableApplicationContext command(Map<String, String> variables, Map<String, String> systemProperties,
			String... args) {
		return application(variables, systemProperties, null).profiles("provision-admin").run(args);
	}

	private SpringApplicationBuilder application(Map<String, String> variables, @Nullable WebApplicationType type) {
		return application(variables, Map.of(), type);
	}

	/**
	 * Replaces the process-environment property source with {@code variables} (plus the isolated
	 * test database and an ephemeral JWT key), as production supplies everything as environment
	 * variables, and overlays {@code systemProperties} without touching the JVM's own.
	 */
	private SpringApplicationBuilder application(Map<String, String> variables, Map<String, String> systemProperties,
			@Nullable WebApplicationType type) {
		Map<String, Object> environment = new HashMap<>(Map.of("spring.datasource.url", database.getJdbcUrl(),
				"spring.datasource.username", database.getUsername(), "spring.datasource.password",
				database.getPassword(), "app.security.jwt.allow-ephemeral-key", "true", "server.port", "0"));
		environment.putAll(variables);
		StandardEnvironment env = new StandardEnvironment();
		env.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
				new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, environment));
		if (!systemProperties.isEmpty()) {
			Map<String, Object> properties = new HashMap<>(env.getSystemProperties());
			properties.putAll(systemProperties);
			env.getPropertySources().replace(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
					new MapPropertySource(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, properties));
		}
		SpringApplicationBuilder builder = new SpringApplicationBuilder(BakedAndBrownedApplication.class)
			.environment(env);
		return type == null ? builder : builder.web(type);
	}

}
