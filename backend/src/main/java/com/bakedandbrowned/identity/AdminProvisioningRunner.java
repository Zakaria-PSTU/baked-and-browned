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
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.bakedandbrowned.common.value.PhoneNumber;
import com.bakedandbrowned.identity.service.AdminProvisioningService;

/**
 * Operator-run one-off command that creates an ADMIN, active only under the {@code provision-admin}
 * profile. That profile starts no web server and no scheduled jobs, and the notification and
 * outbox delivery beans are left out (ADR-0024).
 * <ul>
 * <li>{@code ADMIN_PROVISION_MODE=first} (the default): the first ADMIN, from {@code ADMIN_FULL_NAME},
 * {@code ADMIN_PHONE}, optional {@code ADMIN_EMAIL} and {@code ADMIN_PASSWORD}.</li>
 * <li>{@code ADMIN_PROVISION_MODE=additional}: the same for the new ADMIN, plus
 * {@code PROVISIONER_IDENTIFIER} and {@code PROVISIONER_PASSWORD} of an existing enabled ADMIN who
 * authorizes it.</li>
 * </ul>
 * Passwords come from the environment or, preferably, from a secret file named by
 * {@code ADMIN_PASSWORD_FILE} / {@code PROVISIONER_PASSWORD_FILE} (for example a mounted container
 * secret). They are refused on the command line, where process listings and shell history would
 * show them, and they are never logged. Any refusal fails startup, so the process exits non-zero.
 * See README, "Provisioning admins".
 */
@Component
@Profile("provision-admin")
class AdminProvisioningRunner implements ApplicationRunner {

	static final List<String> SECRETS = List.of("ADMIN_PASSWORD", "PROVISIONER_PASSWORD");

	private static final Logger log = LoggerFactory.getLogger(AdminProvisioningRunner.class);

	private final AdminProvisioningService provisioning;

	private final Environment environment;

	AdminProvisioningRunner(AdminProvisioningService provisioning, Environment environment) {
		this.provisioning = provisioning;
		this.environment = environment;
	}

	@Override
	public void run(ApplicationArguments args) {
		for (String secret : SECRETS) {
			if (args.containsOption(secret)) {
				throw new IllegalStateException(secret + " must not be passed on the command line; set it in the"
						+ " environment or in a file named by " + secret + "_FILE");
			}
		}
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

	/** A password from {@code NAME_FILE} (one trailing line break removed) or {@code NAME}, never both. */
	private String secret(String name) {
		String file = environment.getProperty(name + "_FILE");
		@Nullable String direct = environment.getProperty(name);
		if (file != null && !file.isBlank()) {
			if (direct != null && !direct.isEmpty()) {
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
		return required(name);
	}

	private String required(String name) {
		String value = environment.getProperty(name);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(name + " must be set in the environment");
		}
		return value;
	}

}
