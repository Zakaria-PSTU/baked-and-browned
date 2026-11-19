package com.bakedandbrowned.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.bakedandbrowned.common.value.PhoneNumber;
import com.bakedandbrowned.identity.service.AdminProvisioningService;
import com.bakedandbrowned.identity.service.AdminProvisioningService.ProvisioningRefused;

/**
 * The provision-admin command's service (ADR-0013, ADR-0024): the bootstrap ADMIN, additional
 * ADMINs authorized by an existing enabled ADMIN, no conversion of existing accounts, and an audit
 * record for every attempt without secrets.
 */
class AdminProvisioningIntegrationTests extends IdentityIntegrationTest {

	private static final String FIRST_PHONE = "+8801711000101";

	private static final String ADMIN_PASSWORD = "first-admin-password-1";

	private static final String NEW_PASSWORD = "second-admin-password-2";

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

	// ------------------------------------------------------------------ audit of the bootstrap

	@Test
	void theFirstAdminIsAuditedAsASystemAction() {
		UUID id = firstAdmin();

		Map<String, Object> row = onlyAudit(AdminProvisioningService.PROVISIONED);
		assertThat(row).containsEntry("actor_type", "SYSTEM").containsEntry("entity_type", "USER").containsEntry("entity_id", id);
		assertThat(row.get("actor_user_id")).isNull();
		assertThat((String) row.get("details")).contains("\"mode\": \"FIRST\"", "\"outcome\": \"SUCCEEDED\"",
				"\"targetPhone\": \"***101\"");
	}

	@Test
	void aRefusedBootstrapIsAudited() {
		firstAdmin();

		assertThatIllegalStateException()
			.isThrownBy(() -> provisioning.provisionFirstAdmin("Second Admin", PhoneNumber.of(phone()), null, PASSWORD));
		assertThat(jdbc.queryForMap("SELECT actor_type::text AS actor_type, reason FROM audit_events WHERE action = ?",
				AdminProvisioningService.REFUSED))
			.containsEntry("actor_type", "SYSTEM")
			.containsEntry("reason", AdminProvisioningService.ADMIN_ALREADY_EXISTS);
	}

	// ------------------------------------------------------------------ additional admins

	@Test
	void anEnabledAdminProvisionsAnotherWhoCanSignIn() throws Exception {
		UUID first = firstAdmin();
		String phone = phone();

		UUID second = provisioning.provisionAdditionalAdmin(FIRST_PHONE, ADMIN_PASSWORD, " Second Admin ",
				PhoneNumber.of(phone), "Second@Example.com", NEW_PASSWORD);

		assertThat(jdbc.queryForMap("SELECT full_name, email, role::text AS role FROM users WHERE id = ?", second))
			.containsEntry("full_name", "Second Admin")
			.containsEntry("email", "second@example.com")
			.containsEntry("role", "ADMIN");
		Map<String, Object> row = jdbc.queryForMap("""
				SELECT actor_type::text AS actor_type, actor_user_id, details::text AS details
				FROM audit_events WHERE action = ? AND entity_id = ?""", AdminProvisioningService.PROVISIONED, second);
		assertThat(row).containsEntry("actor_type", "ADMIN").containsEntry("actor_user_id", first);
		assertThat((String) row.get("details")).contains("\"mode\": \"ADDITIONAL\"", "\"outcome\": \"SUCCEEDED\"",
				"\"provisionedBy\": \"" + first + "\"");
		assertThat(login(phone, NEW_PASSWORD).body().get("user").get("role").asString()).isEqualTo("ADMIN");
	}

	@Test
	void theProvisionerMayBeIdentifiedByEmail() {
		provisioning.provisionFirstAdmin("Store Admin", PhoneNumber.of(FIRST_PHONE), "owner@example.com", ADMIN_PASSWORD);

		UUID second = provisioning.provisionAdditionalAdmin("Owner@Example.com", ADMIN_PASSWORD, "Second Admin",
				PhoneNumber.of(phone()), null, NEW_PASSWORD);
		assertThat(role(second)).isEqualTo("ADMIN");
	}

	@Test
	void unknownWrongPasswordCustomerAndDisabledProvisionersAreRefusedAndAudited() throws Exception {
		UUID first = firstAdmin();
		String customerPhone = register(phone(), null);
		UUID customer = jdbc.queryForObject("SELECT id FROM users WHERE phone = ?", UUID.class, customerPhone);

		expectRefused(AdminProvisioningService.PROVISIONER_NOT_FOUND, "+8801799999999", ADMIN_PASSWORD);
		expectRefused(AdminProvisioningService.PROVISIONER_NOT_FOUND, "not-an-identifier", ADMIN_PASSWORD);
		expectRefused(AdminProvisioningService.PROVISIONER_BAD_PASSWORD, FIRST_PHONE, "wrong-password-123");
		expectRefused(AdminProvisioningService.PROVISIONER_NOT_ADMIN, customerPhone, PASSWORD);
		jdbc.update("UPDATE users SET enabled = false WHERE id = ?", first);
		expectRefused(AdminProvisioningService.PROVISIONER_DISABLED, FIRST_PHONE, ADMIN_PASSWORD);

		assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE role = 'ADMIN'", Integer.class)).isEqualTo(1);
		assertThat(role(customer)).isEqualTo("CUSTOMER");
		List<Map<String, Object>> refusals = jdbc.queryForList("""
				SELECT actor_type::text AS actor_type, actor_user_id, reason, details::text AS details
				FROM audit_events WHERE action = ? ORDER BY created_at""", AdminProvisioningService.REFUSED);
		assertThat(refusals).extracting(r -> r.get("reason"))
			.containsExactly(AdminProvisioningService.PROVISIONER_NOT_FOUND, AdminProvisioningService.PROVISIONER_NOT_FOUND,
					AdminProvisioningService.PROVISIONER_BAD_PASSWORD, AdminProvisioningService.PROVISIONER_NOT_ADMIN,
					AdminProvisioningService.PROVISIONER_DISABLED);
		// Not authorized, so recorded as SYSTEM; the account the credentials named is kept for review.
		assertThat(refusals).allSatisfy(r -> {
			assertThat(r.get("actor_type")).isEqualTo("SYSTEM");
			assertThat(r.get("actor_user_id")).isNull();
		});
		assertThat((String) refusals.get(2).get("details")).contains("\"claimedProvisionerId\": \"" + first + "\"");
		assertThat((String) refusals.get(3).get("details")).contains("\"claimedProvisionerId\": \"" + customer + "\"");
	}

	@Test
	void additionalModeCannotBootstrapTheFirstAdmin() throws Exception {
		String customerPhone = register(phone(), null);

		expectRefused(AdminProvisioningService.PROVISIONER_NOT_ADMIN, customerPhone, PASSWORD);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE role = 'ADMIN'", Integer.class)).isZero();
	}

	@Test
	void anExistingCustomerIsNeverConvertedToAnAdmin() throws Exception {
		UUID first = firstAdmin();
		String customerPhone = phone();
		register(customerPhone, "nusrat@example.com");

		assertThatIllegalStateException()
			.isThrownBy(() -> provisioning.provisionAdditionalAdmin(FIRST_PHONE, ADMIN_PASSWORD, "Nusrat Jahan",
					PhoneNumber.of(customerPhone), null, NEW_PASSWORD))
			.withMessageContaining("never made ADMIN");
		assertThatIllegalStateException()
			.isThrownBy(() -> provisioning.provisionAdditionalAdmin(FIRST_PHONE, ADMIN_PASSWORD, "Nusrat Jahan",
					PhoneNumber.of(phone()), "NUSRAT@example.com", NEW_PASSWORD));

		assertThat(jdbc.queryForObject("SELECT role::text FROM users WHERE phone = ?", String.class, customerPhone))
			.isEqualTo("CUSTOMER");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE role = 'ADMIN'", Integer.class)).isEqualTo(1);
		// Authorized but refused: recorded against the provisioner.
		assertThat(jdbc.queryForList("SELECT actor_user_id FROM audit_events WHERE action = ? AND reason = ?", UUID.class,
				AdminProvisioningService.REFUSED, AdminProvisioningService.ACCOUNT_EXISTS))
			.containsExactly(first, first);
	}

	@Test
	void aWeakPasswordForTheNewAdminIsRefusedAndAudited() {
		firstAdmin();

		assertThatIllegalArgumentException().isThrownBy(() -> provisioning.provisionAdditionalAdmin(FIRST_PHONE,
				ADMIN_PASSWORD, "Second Admin", PhoneNumber.of(phone()), null, "too-short"));
		assertThat(jdbc.queryForList("SELECT reason FROM audit_events WHERE action = ?", String.class,
				AdminProvisioningService.REFUSED))
			.containsExactly(AdminProvisioningService.WEAK_PASSWORD);
	}

	@Test
	void concurrentRunsForOneTargetCreateExactlyOneAdmin() throws Exception {
		firstAdmin();
		PhoneNumber target = PhoneNumber.of(phone());
		int threads = 4;
		CountDownLatch start = new CountDownLatch(1);
		List<Future<String>> outcomes = new ArrayList<>();
		try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
			for (int i = 0; i < threads; i++) {
				outcomes.add(pool.submit(() -> {
					start.await();
					try {
						provisioning.provisionAdditionalAdmin(FIRST_PHONE, ADMIN_PASSWORD, "Second Admin", target, null,
								NEW_PASSWORD);
						return "CREATED";
					}
					catch (ProvisioningRefused ex) {
						return ex.code();
					}
				}));
			}
			start.countDown();
			List<String> results = new ArrayList<>();
			for (Future<String> outcome : outcomes) {
				results.add(outcome.get(60, TimeUnit.SECONDS));
			}
			assertThat(results).containsOnlyOnce("CREATED")
				.filteredOn(r -> !r.equals("CREATED"))
				.containsOnly(AdminProvisioningService.ACCOUNT_EXISTS);
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE phone = ?", Integer.class, target.value()))
			.isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = ?", Integer.class,
				AdminProvisioningService.REFUSED))
			.isEqualTo(threads - 1);
	}

	@Test
	void auditRecordsNeverContainPasswordsOrFullContactDetails() {
		firstAdmin();
		String phone = phone();
		provisioning.provisionAdditionalAdmin(FIRST_PHONE, ADMIN_PASSWORD, "Second Admin", PhoneNumber.of(phone),
				"second@example.com", NEW_PASSWORD);
		expectRefused(AdminProvisioningService.PROVISIONER_BAD_PASSWORD, FIRST_PHONE, "a-guessed-password-9");

		String everything = String.join("\n",
				jdbc.queryForList("SELECT coalesce(reason, '') || ' ' || details::text FROM audit_events", String.class));
		assertThat(everything).doesNotContain(ADMIN_PASSWORD, NEW_PASSWORD, "a-guessed-password-9", phone,
				"second@example.com", FIRST_PHONE, "argon2");
	}

	// ------------------------------------------------------------------ helpers

	private UUID firstAdmin() {
		return provisioning.provisionFirstAdmin("Store Admin", PhoneNumber.of(FIRST_PHONE), null, ADMIN_PASSWORD);
	}

	private void expectRefused(String code, String provisionerIdentifier, String provisionerPassword) {
		assertThatIllegalStateException()
			.isThrownBy(() -> provisioning.provisionAdditionalAdmin(provisionerIdentifier, provisionerPassword,
					"Second Admin", PhoneNumber.of(phone()), null, NEW_PASSWORD))
			.isInstanceOfSatisfying(ProvisioningRefused.class, ex -> assertThat(ex.code()).isEqualTo(code))
			.withMessageNotContaining(provisionerPassword);
	}

	private Map<String, Object> onlyAudit(String action) {
		return jdbc.queryForMap("""
				SELECT actor_type::text AS actor_type, actor_user_id, entity_type, entity_id, details::text AS details
				FROM audit_events WHERE action = ?""", action);
	}

	private String role(UUID id) {
		return jdbc.queryForObject("SELECT role::text FROM users WHERE id = ?", String.class, id);
	}

}
