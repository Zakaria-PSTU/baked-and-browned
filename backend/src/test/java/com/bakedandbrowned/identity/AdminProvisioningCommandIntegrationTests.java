package com.bakedandbrowned.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import com.bakedandbrowned.BakedAndBrownedApplication;
import com.bakedandbrowned.identity.service.AdminProvisioningService;
import com.bakedandbrowned.identity.service.AdminProvisioningService.ProvisioningRefused;

/**
 * Runs the real {@code provision-admin} command (the operator's only supported way to create
 * ADMINs) against the test database: no web server, first and additional modes, and a refusal
 * that fails startup so the process would exit non-zero.
 * <p>
 * The {@code test} profile is added only because no production {@code SmsSender} exists yet (the
 * fake one is dev/test only), so the application context, the command included, cannot start
 * without it. That is a separate deployment blocker, not something this test hides: it adds a bean,
 * not a web server, and the assertions still check that none starts.
 */
class AdminProvisioningCommandIntegrationTests extends IdentityIntegrationTest {

	private static final String FIRST_PHONE = "+8801711000201";

	private static final String FIRST_PASSWORD = "first-admin-password-1";

	@Autowired
	private JdbcConnectionDetails database;

	@Test
	void bothModesRunWithoutAWebServer() {
		try (ConfigurableApplicationContext first = command(Map.of("ADMIN_FULL_NAME", "Store Admin", "ADMIN_PHONE",
				FIRST_PHONE, "ADMIN_PASSWORD", FIRST_PASSWORD))) {
			assertThat(first).isNotInstanceOf(WebServerApplicationContext.class);
		}
		try (ConfigurableApplicationContext additional = command(Map.of("ADMIN_PROVISION_MODE", "additional",
				"PROVISIONER_IDENTIFIER", FIRST_PHONE, "PROVISIONER_PASSWORD", FIRST_PASSWORD, "ADMIN_FULL_NAME",
				"Second Admin", "ADMIN_PHONE", "+8801711000202", "ADMIN_PASSWORD", "second-admin-password-2"))) {
			assertThat(additional).isNotInstanceOf(WebServerApplicationContext.class);
		}

		assertThat(jdbc.queryForList("SELECT phone FROM users WHERE role = 'ADMIN' ORDER BY created_at", String.class))
			.containsExactly(FIRST_PHONE, "+8801711000202");
		assertThat(jdbc.queryForList("SELECT actor_type::text FROM audit_events WHERE action = ? ORDER BY created_at",
				String.class, AdminProvisioningService.PROVISIONED))
			.containsExactly("SYSTEM", "ADMIN");
	}

	@Test
	void aRefusedProvisioningFailsTheCommand() {
		command(Map.of("ADMIN_FULL_NAME", "Store Admin", "ADMIN_PHONE", FIRST_PHONE, "ADMIN_PASSWORD", FIRST_PASSWORD))
			.close();

		assertThatThrownBy(() -> command(Map.of("ADMIN_PROVISION_MODE", "additional", "PROVISIONER_IDENTIFIER",
				FIRST_PHONE, "PROVISIONER_PASSWORD", "not-the-password-1", "ADMIN_FULL_NAME", "Second Admin",
				"ADMIN_PHONE", "+8801711000202", "ADMIN_PASSWORD", "second-admin-password-2")))
			.isInstanceOf(ProvisioningRefused.class);
		assertThatThrownBy(() -> command(Map.of("ADMIN_PROVISION_MODE", "promote", "ADMIN_FULL_NAME", "Second Admin",
				"ADMIN_PHONE", "+8801711000202", "ADMIN_PASSWORD", "second-admin-password-2")))
			.isInstanceOf(IllegalStateException.class).hasMessage("ADMIN_PROVISION_MODE must be 'first' or 'additional'");

		assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE role = 'ADMIN'", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForList("SELECT reason FROM audit_events WHERE action = ?", String.class,
				AdminProvisioningService.REFUSED))
			.containsExactly(AdminProvisioningService.PROVISIONER_BAD_PASSWORD);
	}

	/** The command as an operator runs it, with {@code variables} standing in for the environment. */
	private ConfigurableApplicationContext command(Map<String, String> variables) {
		List<String> args = new ArrayList<>(List.of("--spring.datasource.url=" + database.getJdbcUrl(),
				"--spring.datasource.username=" + database.getUsername(),
				"--spring.datasource.password=" + database.getPassword(), "--app.security.jwt.allow-ephemeral-key=true",
				"--app.payments.proof-storage.directory=target/test-payment-proofs", "--server.port=0"));
		variables.forEach((name, value) -> args.add("--" + name + "=" + value));
		return new SpringApplicationBuilder(BakedAndBrownedApplication.class).profiles("provision-admin", "test")
			.run(args.toArray(String[]::new));
	}

}
