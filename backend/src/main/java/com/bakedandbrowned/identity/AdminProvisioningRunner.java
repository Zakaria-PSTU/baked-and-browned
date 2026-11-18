package com.bakedandbrowned.identity;

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
 * Operator-run one-off command that creates the first ADMIN from environment variables
 * ({@code ADMIN_FULL_NAME}, {@code ADMIN_PHONE}, optional {@code ADMIN_EMAIL}, {@code ADMIN_PASSWORD}),
 * active only under the {@code provision-admin} profile, which also disables the web server. Any
 * refusal fails startup, so the process exits non-zero. See README, "Provisioning the first admin".
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
		UUID id = provisioning.provisionFirstAdmin(required("ADMIN_FULL_NAME"), PhoneNumber.of(required("ADMIN_PHONE")),
				environment.getProperty("ADMIN_EMAIL"), required("ADMIN_PASSWORD"));
		log.info("Provisioned the first ADMIN account {}", id);
	}

	private String required(String name) {
		String value = environment.getProperty(name);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(name + " must be set in the environment");
		}
		return value;
	}

}
