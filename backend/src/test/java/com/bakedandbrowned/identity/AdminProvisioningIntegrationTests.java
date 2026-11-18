package com.bakedandbrowned.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.bakedandbrowned.common.value.PhoneNumber;
import com.bakedandbrowned.identity.service.AdminProvisioningService;

class AdminProvisioningIntegrationTests extends IdentityIntegrationTest {

	@Autowired
	private AdminProvisioningService provisioning;

	@Test
	void provisionsTheFirstAdminOnlyOnce() {
		UUID id = provisioning.provisionFirstAdmin(" Store Admin ", PhoneNumber.of(phone()), "Admin@Example.com", PASSWORD);

		assertThat(jdbc.queryForMap("SELECT full_name, email, role::text AS role, password_hash FROM users WHERE id = ?", id))
			.containsEntry("full_name", "Store Admin")
			.containsEntry("email", "admin@example.com")
			.containsEntry("role", "ADMIN")
			.hasEntrySatisfying("password_hash", hash -> assertThat((String) hash).startsWith("$argon2id$"));

		assertThatIllegalStateException()
			.isThrownBy(() -> provisioning.provisionFirstAdmin("Second Admin", PhoneNumber.of(phone()), null, PASSWORD))
			.withMessageContaining("already exists");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE role = 'ADMIN'", Integer.class)).isEqualTo(1);
	}

	@Test
	void refusesTakenPhoneAndWeakPasswords() throws Exception {
		String customerPhone = register(phone(), null);

		assertThatIllegalStateException()
			.isThrownBy(() -> provisioning.provisionFirstAdmin("Store Admin", PhoneNumber.of(customerPhone), null, PASSWORD));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> provisioning.provisionFirstAdmin("Store Admin", PhoneNumber.of(phone()), null, "too-short"));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE role = 'ADMIN'", Integer.class)).isZero();
	}

}
