package com.bakedandbrowned.identity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.stereotype.Component;

import com.bakedandbrowned.common.value.PhoneNumber;
import com.bakedandbrowned.identity.service.AdminProvisioningService;

/**
 * Operator-run one-off command that creates an ADMIN, active only under the {@code provision-admin}
 * profile. That profile starts no web server and no scheduled jobs, and the notification and
 * outbox delivery beans are left out (ADR-0024). The command refuses to run if a web server was
 * started anyway, so the profile can never serve traffic without notification delivery.
 * <ul>
 * <li>{@code ADMIN_PROVISION_MODE=first} (the default): the first ADMIN, from {@code ADMIN_FULL_NAME},
 * {@code ADMIN_PHONE}, optional {@code ADMIN_EMAIL} and {@code ADMIN_PASSWORD}.</li>
 * <li>{@code ADMIN_PROVISION_MODE=additional}: the same for the new ADMIN, plus
 * {@code PROVISIONER_IDENTIFIER} and {@code PROVISIONER_PASSWORD} of an existing enabled ADMIN who
 * authorizes it.</li>
 * </ul>
 * Passwords are read <b>only</b> from a secret file named by {@code ADMIN_PASSWORD_FILE} /
 * {@code PROVISIONER_PASSWORD_FILE} (preferred, for example a mounted container secret) or from the
 * process environment. A password found in any other configuration source (command-line
 * arguments, {@code -D} system properties, {@code spring.application.json}, configuration files) is
 * refused, because process listings, shell history or the repository would show it. Passwords are
 * never logged or put in exception messages. Any refusal fails startup, so the process exits
 * non-zero. See README, "Provisioning admins".
 */
@Component
@Profile("provision-admin")
class AdminProvisioningRunner implements ApplicationRunner {

	static final List<String> SECRETS = List.of("ADMIN_PASSWORD", "PROVISIONER_PASSWORD");

	/** Spring Boot's attached view over all property sources (ConfigurationPropertySources). */
	private static final String ATTACHED_VIEW = "configurationProperties";

	private static final Logger log = LoggerFactory.getLogger(AdminProvisioningRunner.class);

	private final AdminProvisioningService provisioning;

	private final ConfigurableEnvironment environment;

	private final ApplicationContext context;

	AdminProvisioningRunner(AdminProvisioningService provisioning, ConfigurableEnvironment environment,
			ApplicationContext context) {
		this.provisioning = provisioning;
		this.environment = environment;
		this.context = context;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (context instanceof WebServerApplicationContext) {
			throw new IllegalStateException("provision-admin must not run with a web server; it leaves out notification"
					+ " delivery. Do not override spring.main.web-application-type for this command.");
		}
		refuseSecretsOutsideTheProcessEnvironment();
		String mode = environment.getProperty("ADMIN_PROVISION_MODE", "first").strip().toLowerCase(Locale.ROOT);
		switch (mode) {
			case "first" -> {
				UUID id = provisioning.provisionFirstAdmin(required("ADMIN_FULL_NAME"),
						PhoneNumber.of(required("ADMIN_PHONE")), environment.getProperty("ADMIN_EMAIL"),
						secret("ADMIN_PASSWORD"));
				log.info("Provisioned the first ADMIN account {}", id);
			}
			case "additional" -> {
				UUID id = provisioning.provisionAdditionalAdmin(required("PROVISIONER_IDENTIFIER"),
						secret("PROVISIONER_PASSWORD"), required("ADMIN_FULL_NAME"),
						PhoneNumber.of(required("ADMIN_PHONE")), environment.getProperty("ADMIN_EMAIL"),
						secret("ADMIN_PASSWORD"));
				log.info("Provisioned an additional ADMIN account {}", id);
			}
			default -> throw new IllegalStateException("ADMIN_PROVISION_MODE must be 'first' or 'additional'");
		}
	}

	/** Names the source, never the value. */
	private void refuseSecretsOutsideTheProcessEnvironment() {
		for (PropertySource<?> source : environment.getPropertySources()) {
			String sourceName = source.getName();
			if (sourceName.equals(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
					|| sourceName.equals(ATTACHED_VIEW)) {
				continue; // the process environment itself, or the view that merely wraps all sources
			}
			for (String secret : SECRETS) {
				if (source.containsProperty(secret)) {
					throw new IllegalStateException(secret + " must not be set in " + describe(sourceName)
							+ "; provide it as " + secret + "_FILE (a secret file) or in the process environment");
				}
			}
		}
	}

	private static String describe(String sourceName) {
		return switch (sourceName) {
			case "commandLineArgs" -> "a command-line argument";
			case StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME -> "a JVM system property (-D)";
			default -> "the configuration source '" + sourceName + "'";
		};
	}

	/**
	 * A password from the file named by {@code NAME_FILE} (one trailing line break removed) or from
	 * the process environment variable {@code NAME}, never both.
	 */
	private String secret(String name) {
		String file = environment.getProperty(name + "_FILE");
		@Nullable Object direct = processEnvironment().getProperty(name);
		if (file != null && !file.isBlank()) {
			if (direct != null && !direct.toString().isEmpty()) {
				throw new IllegalStateException("Set either " + name + " or " + name + "_FILE, not both");
			}
			try {
				String value = Files.readString(Path.of(file.strip()), StandardCharsets.UTF_8);
				value = value.endsWith("\r\n") ? value.substring(0, value.length() - 2)
						: value.endsWith("\n") ? value.substring(0, value.length() - 1) : value;
				if (value.isEmpty()) {
					throw new IllegalStateException(name + "_FILE names an empty file");
				}
				return value;
			}
			catch (IOException ex) {
				throw new IllegalStateException(name + "_FILE could not be read (" + ex.getClass().getSimpleName() + ")");
			}
		}
		if (direct == null || direct.toString().isBlank()) {
			throw new IllegalStateException(name + "_FILE or " + name + " must be set in the environment");
		}
		return direct.toString();
	}

	private PropertySource<?> processEnvironment() {
		PropertySource<?> source = environment.getPropertySources()
			.get(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
		if (source == null) {
			throw new IllegalStateException("The process environment is not available");
		}
		return source;
	}

	private String required(String name) {
		String value = environment.getProperty(name);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(name + " must be set in the environment");
		}
		return value;
	}

}
