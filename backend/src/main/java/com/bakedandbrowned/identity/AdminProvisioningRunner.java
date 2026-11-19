package com.bakedandbrowned.identity;

import java.util.Locale;
import java.util.UUID;

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
 * Operator-run one-off command that creates an ADMIN from environment variables, active only under
 * the {@code provision-admin} profile, which also disables the web server and scheduled jobs.
 * <ul>
 * <li>{@code ADMIN_PROVISION_MODE=first} (the default): the first ADMIN, from {@code ADMIN_FULL_NAME},
 * {@code ADMIN_PHONE}, optional {@code ADMIN_EMAIL} and {@code ADMIN_PASSWORD}.</li>
 * <li>{@code ADMIN_PROVISION_MODE=additional}: the same variables for the new ADMIN, plus
 * {@code PROVISIONER_IDENTIFIER} and {@code PROVISIONER_PASSWORD} of an existing enabled ADMIN who
 * authorizes it (ADR-0024).</li>
 * </ul>
 * Any refusal fails startup, so the process exits non-zero. Secrets are never logged. See README,
 * "Provisioning admins".
 */
@Component
@Profile("provision-admin")
class AdminProvisioningRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(AdminProvisioningRunner.class);

	private final AdminProvisioningService provisioning;

	private final Environment environment;

	AdminProvisioningRunner(AdminProvisioningService provisioning, Environment environment) {
		this.provisioning = provisioning;
		this.environment = environment;
	}

	@Override
	public void run(ApplicationArguments args) {
		String mode = environment.getProperty("ADMIN_PROVISION_MODE", "first").strip().toLowerCase(Locale.ROOT);
		switch (mode) {
			case "first" -> {
				UUID id = provisioning.provisionFirstAdmin(required("ADMIN_FULL_NAME"),
						PhoneNumber.of(required("ADMIN_PHONE")), environment.getProperty("ADMIN_EMAIL"),
						required("ADMIN_PASSWORD"));
				log.info("Provisioned the first ADMIN account {}", id);
			}
			case "additional" -> {
				UUID id = provisioning.provisionAdditionalAdmin(required("PROVISIONER_IDENTIFIER"),
						required("PROVISIONER_PASSWORD"), required("ADMIN_FULL_NAME"),
						PhoneNumber.of(required("ADMIN_PHONE")), environment.getProperty("ADMIN_EMAIL"),
						required("ADMIN_PASSWORD"));
				log.info("Provisioned an additional ADMIN account {}", id);
			}
			default -> throw new IllegalStateException("ADMIN_PROVISION_MODE must be 'first' or 'additional'");
		}
	}

	private String required(String name) {
		String value = environment.getProperty(name);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(name + " must be set in the environment");
		}
		return value;
	}

}
