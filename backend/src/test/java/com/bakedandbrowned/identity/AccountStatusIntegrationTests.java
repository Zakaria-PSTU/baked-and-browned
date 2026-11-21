package com.bakedandbrowned.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.http.HttpHeaders;

import com.bakedandbrowned.common.value.PhoneNumber;
import com.bakedandbrowned.identity.service.AdminProvisioningService;
import com.bakedandbrowned.identity.service.AdminProvisioningService.InvalidAdminInput;
import com.bakedandbrowned.identity.service.AdminProvisioningService.ProvisioningRefused;
import com.bakedandbrowned.identity.service.AdminProvisioningService.StatusChange;

/**
 * ADR-0029: an enabled ADMIN disables or re-enables an account through the operator command.
 * Disabling revokes every session, re-enabling never revives one, the last enabled ADMIN is never
 * disabled, and every attempt is audited once.
 */
class AccountStatusIntegrationTests extends IdentityIntegrationTest {

	private static final String ADMIN_PHONE = "+8801711000201";

	private static final String ADMIN_PASSWORD = "first-admin-password-1";

	private static final String REASON = "Phone reported stolen";

	@Autowired
	private AdminProvisioningService provisioning;

	@Test
	void disablingEndsEverySessionAndReEnablingRevivesNone() throws Exception {
		UUID adminId = firstAdmin();
		String phone = register(phone(), null);
		Session laptop = login(phone, PASSWORD);
		Session mobile = login(phone, PASSWORD);

		StatusChange disabled = provisioning.disableAccount(ADMIN_PHONE, ADMIN_PASSWORD, phone, REASON);

		assertThat(disabled.sessionsRevoked()).isEqualTo(2);
		assertThat(enabled(disabled.accountId())).isFalse();
		assertThat(liveTokens(disabled.accountId())).isZero();
		// The access token is refused on its next request (ADR-0028), and so is every refresh.
		mockMvc.perform(get("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, laptop.bearer()))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(refreshRequest(laptop)).andExpect(status().isUnauthorized());
		mockMvc.perform(refreshRequest(mobile)).andExpect(status().isUnauthorized());

		StatusChange enabled = provisioning.enableAccount(ADMIN_PHONE, ADMIN_PASSWORD, phone, "Phone recovered");

		assertThat(enabled.sessionsRevoked()).isZero();
		assertThat(enabled(enabled.accountId())).isTrue();
		// A token from before the disable stays dead; the user signs in again.
		mockMvc.perform(refreshRequest(mobile)).andExpect(status().isUnauthorized());
		Session again = login(phone, PASSWORD);
		mockMvc.perform(get("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, again.bearer()))
			.andExpect(status().isOk());

		List<Map<String, Object>> records = jdbc.queryForList("""
				SELECT action, actor_type::text AS actor_type, actor_user_id, entity_id, reason, details::text AS details
				FROM audit_events WHERE action LIKE 'account.%' ORDER BY created_at, id""");
		assertThat(records).extracting(r -> r.get("action"))
			.containsExactly(AdminProvisioningService.ACCOUNT_DISABLED, AdminProvisioningService.ACCOUNT_ENABLED);
		assertThat(records).allSatisfy(r -> {
			assertThat(r.get("actor_type")).isEqualTo("ADMIN");
			assertThat(r.get("actor_user_id")).isEqualTo(adminId);
			assertThat(r.get("entity_id")).isEqualTo(disabled.accountId());
			assertThat((String) r.get("details")).doesNotContain(phone).doesNotContain(ADMIN_PASSWORD);
		});
		assertThat(records.get(0).get("reason")).isEqualTo(REASON);
		assertThat((String) records.get(0).get("details")).contains("\"sessionsRevoked\": 2", "\"accountRole\": \"CUSTOMER\"");
	}

	@Test
	void anAccountMayBeNamedByEmail() throws Exception {
		firstAdmin();
		String phone = register(phone(), "Rina@Example.com");

		StatusChange disabled = provisioning.disableAccount(ADMIN_PHONE, ADMIN_PASSWORD, "rina@example.com", REASON);

		assertThat(jdbc.queryForObject("SELECT phone FROM users WHERE id = ?", String.class, disabled.accountId()))
			.isEqualTo(phone);
		assertThat(enabled(disabled.accountId())).isFalse();
	}

	@Test
	void theLastEnabledAdminIsNeverDisabled() throws Exception {
		UUID first = firstAdmin();
		refused(AdminProvisioningService.LAST_ENABLED_ADMIN,
				() -> provisioning.disableAccount(ADMIN_PHONE, ADMIN_PASSWORD, ADMIN_PHONE, REASON));
		assertThat(enabled(first)).isTrue();

		// With a second ADMIN, either may disable the other, but not the one left.
		String secondPhone = phone();
		provisioning.provisionAdditionalAdmin(ADMIN_PHONE, ADMIN_PASSWORD, "Second Admin", PhoneNumber.of(secondPhone),
				null, "second-admin-password-2");
		provisioning.disableAccount(secondPhone, "second-admin-password-2", ADMIN_PHONE, "Left the business");
		assertThat(enabled(first)).isFalse();
		refused(AdminProvisioningService.LAST_ENABLED_ADMIN,
				() -> provisioning.disableAccount(secondPhone, "second-admin-password-2", secondPhone, REASON));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE role = 'ADMIN' AND enabled", Integer.class))
			.isEqualTo(1);
	}

	@Test
	void twoAdminsDisablingEachOtherAtOnceLeaveOneEnabled() throws Exception {
		firstAdmin();
		String secondPhone = phone();
		provisioning.provisionAdditionalAdmin(ADMIN_PHONE, ADMIN_PASSWORD, "Second Admin", PhoneNumber.of(secondPhone),
				null, "second-admin-password-2");

		List<String> outcomes = together(
				() -> outcome(() -> provisioning.disableAccount(ADMIN_PHONE, ADMIN_PASSWORD, secondPhone, REASON)),
				() -> outcome(() -> provisioning.disableAccount(secondPhone, "second-admin-password-2", ADMIN_PHONE,
						REASON)));

		// The second run finds its own provisioner disabled, or its target the last enabled ADMIN.
		assertThat(outcomes).containsOnlyOnce("OK");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE role = 'ADMIN' AND enabled", Integer.class))
			.isEqualTo(1);
	}

	@Test
	void refusalsChangeNothingAndAreEachAuditedOnce() throws Exception {
		UUID adminId = firstAdmin();
		String customerPhone = register(phone(), null);
		UUID customer = jdbc.queryForObject("SELECT id FROM users WHERE phone = ?", UUID.class, customerPhone);

		refused(AdminProvisioningService.ACCOUNT_NOT_FOUND,
				() -> provisioning.disableAccount(ADMIN_PHONE, ADMIN_PASSWORD, "+8801799999999", REASON));
		refused(AdminProvisioningService.ACCOUNT_NOT_FOUND,
				() -> provisioning.disableAccount(ADMIN_PHONE, ADMIN_PASSWORD, "not-an-identifier", REASON));
		refused(AdminProvisioningService.ALREADY_ENABLED,
				() -> provisioning.enableAccount(ADMIN_PHONE, ADMIN_PASSWORD, customerPhone, REASON));
		assertThatThrownBy(() -> provisioning.disableAccount(ADMIN_PHONE, ADMIN_PASSWORD, customerPhone, " no "))
			.isInstanceOfSatisfying(InvalidAdminInput.class,
					ex -> assertThat(ex.code()).isEqualTo(AdminProvisioningService.INVALID_REASON));
		refused(AdminProvisioningService.PROVISIONER_BAD_PASSWORD,
				() -> provisioning.disableAccount(ADMIN_PHONE, "wrong-password-123", customerPhone, REASON));
		refused(AdminProvisioningService.PROVISIONER_NOT_ADMIN,
				() -> provisioning.disableAccount(customerPhone, PASSWORD, ADMIN_PHONE, REASON));
		assertThat(enabled(customer)).isTrue();

		provisioning.disableAccount(ADMIN_PHONE, ADMIN_PASSWORD, customerPhone, REASON);
		refused(AdminProvisioningService.ALREADY_DISABLED,
				() -> provisioning.disableAccount(ADMIN_PHONE, ADMIN_PASSWORD, customerPhone, REASON));

		List<Map<String, Object>> refusals = jdbc.queryForList("""
				SELECT actor_type::text AS actor_type, actor_user_id, reason, details::text AS details
				FROM audit_events WHERE action = ? ORDER BY created_at, id""",
				AdminProvisioningService.STATUS_CHANGE_REFUSED);
		assertThat(refusals).extracting(r -> r.get("reason"))
			.containsExactly(AdminProvisioningService.ACCOUNT_NOT_FOUND, AdminProvisioningService.ACCOUNT_NOT_FOUND,
					AdminProvisioningService.ALREADY_ENABLED, AdminProvisioningService.INVALID_REASON,
					AdminProvisioningService.PROVISIONER_BAD_PASSWORD, AdminProvisioningService.PROVISIONER_NOT_ADMIN,
					AdminProvisioningService.ALREADY_DISABLED);
		// Authorized refusals name the ADMIN; refused credentials are recorded as a system event.
		assertThat(refusals.get(0)).containsEntry("actor_type", "ADMIN").containsEntry("actor_user_id", adminId);
		assertThat(refusals.get(4)).containsEntry("actor_type", "SYSTEM");
		assertThat(refusals).allSatisfy(r -> assertThat((String) r.get("details")).doesNotContain(customerPhone)
			.doesNotContain("wrong-password-123")
			.doesNotContain(ADMIN_PASSWORD));
	}

	@Test
	void aDisabledAdminCannotAuthorizeAStatusChange() throws Exception {
		firstAdmin();
		String secondPhone = phone();
		provisioning.provisionAdditionalAdmin(ADMIN_PHONE, ADMIN_PASSWORD, "Second Admin", PhoneNumber.of(secondPhone),
				null, "second-admin-password-2");
		provisioning.disableAccount(ADMIN_PHONE, ADMIN_PASSWORD, secondPhone, "Left the business");

		// It cannot even re-enable itself.
		refused(AdminProvisioningService.PROVISIONER_DISABLED,
				() -> provisioning.enableAccount(secondPhone, "second-admin-password-2", secondPhone, REASON));
		assertThat(jdbc.queryForObject("SELECT enabled FROM users WHERE phone = ?", Boolean.class, secondPhone))
			.isFalse();
	}

	// ------------------------------------------------------------------ helpers

	private UUID firstAdmin() {
		return provisioning.provisionFirstAdmin("Store Admin", PhoneNumber.of(ADMIN_PHONE), null, ADMIN_PASSWORD);
	}

	private boolean enabled(UUID id) {
		return Boolean.TRUE.equals(jdbc.queryForObject("SELECT enabled FROM users WHERE id = ?", Boolean.class, id));
	}

	private int liveTokens(UUID userId) {
		Integer live = jdbc.queryForObject(
				"SELECT count(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL", Integer.class, userId);
		return live == null ? 0 : live;
	}

	private static void refused(String code, Runnable call) {
		assertThatThrownBy(call::run).isInstanceOfSatisfying(ProvisioningRefused.class,
				ex -> assertThat(ex.code()).isEqualTo(code));
	}

	/** "OK", or the refusal code. */
	private static String outcome(Runnable call) {
		try {
			call.run();
			return "OK";
		}
		catch (ProvisioningRefused ex) {
			return ex.code();
		}
	}

	private static List<String> together(java.util.concurrent.Callable<String> first,
			java.util.concurrent.Callable<String> second) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			CountDownLatch start = new CountDownLatch(1);
			Future<String> a = pool.submit(() -> {
				start.await();
				return first.call();
			});
			Future<String> b = pool.submit(() -> {
				start.await();
				return second.call();
			});
			start.countDown();
			return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
		}
		finally {
			pool.shutdown();
		}
	}

}
