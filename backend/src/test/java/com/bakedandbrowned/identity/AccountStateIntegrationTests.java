package com.bakedandbrowned.identity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.ResultActions;

/**
 * ADR-0028: an access token is honoured only for the account as it is now. Disabling an account
 * stops its unexpired token on the next request, and the role comes from the database, not the
 * token.
 */
class AccountStateIntegrationTests extends IdentityIntegrationTest {

	@Test
	void aDisabledAccountsTokenIsRefusedOnItsNextRequest() throws Exception {
		String phone = register(phone(), null);
		Session session = login(phone, PASSWORD);
		as(session, "/api/v1/orders").andExpect(status().isOk());

		jdbc.update("UPDATE users SET enabled = false WHERE phone = ?", phone);

		for (String path : new String[] { "/api/v1/orders", "/api/v1/auth/me", "/api/v1/products" }) {
			as(session, path).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_TOKEN"))
				// Generic: the response does not say the account is disabled.
				.andExpect(jsonPath("$.detail").value("The access token is invalid or has expired."));
		}
		// The public catalogue still answers requests without a token.
		mockMvc.perform(get("/api/v1/products")).andExpect(status().isOk());
		// And the refresh is refused too, so the browser signs the user out.
		mockMvc.perform(refreshRequest(session)).andExpect(status().isUnauthorized());
	}

	@Test
	void reEnablingByHandRevivesNeitherTheTokenNorTheSession() throws Exception {
		// QA F-1 (ADR-0031), by direct SQL: nothing revoked the session here, yet neither the old
		// access token nor a transparent refresh with the old cookie brings it back.
		String phone = register(phone(), null);
		Session session = login(phone, PASSWORD);
		String other = register(phone(), null);
		Session bystander = login(other, PASSWORD);
		jdbc.update("UPDATE users SET enabled = false WHERE phone = ?", phone);
		as(session, "/api/v1/orders").andExpect(status().isUnauthorized());

		jdbc.update("UPDATE users SET enabled = true WHERE phone = ?", phone);
		as(session, "/api/v1/orders").andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
		mockMvc.perform(refreshRequest(session)).andExpect(status().isUnauthorized());
		org.assertj.core.api.Assertions
			.assertThat(jdbc.queryForList("""
					SELECT DISTINCT revoke_reason FROM refresh_tokens t JOIN users u ON u.id = t.user_id
					WHERE u.phone = ?""", String.class, phone))
			.containsExactly("ACCOUNT_DISABLED");

		// A fresh sign-in works; the other user was never affected.
		as(login(phone, PASSWORD), "/api/v1/orders").andExpect(status().isOk());
		as(bystander, "/api/v1/orders").andExpect(status().isOk());
		mockMvc.perform(refreshRequest(bystander)).andExpect(status().isOk());
		org.assertj.core.api.Assertions
			.assertThat(jdbc.queryForObject("SELECT token_version FROM users WHERE phone = ?", Integer.class, other))
			.isZero();
	}

	@Test
	void afterAReEnableTheNewSessionStillDetectsReplay() throws Exception {
		// ADR-0031 keeps strict rotation (ADR-0023, ADR-0025): a replayed token ends the session.
		String phone = register(phone(), null);
		jdbc.update("UPDATE users SET enabled = false WHERE phone = ?", phone);
		jdbc.update("UPDATE users SET enabled = true WHERE phone = ?", phone);
		Session fresh = login(phone, PASSWORD);
		org.springframework.test.web.servlet.MvcResult rotated = mockMvc.perform(refreshRequest(fresh))
			.andExpect(status().isOk())
			.andReturn();
		Session next = Session.from(rotated, json);

		mockMvc.perform(refreshRequest(fresh)).andExpect(status().isUnauthorized());
		mockMvc.perform(refreshRequest(next)).andExpect(status().isUnauthorized());
		org.assertj.core.api.Assertions
			.assertThat(jdbc.queryForList("""
					SELECT DISTINCT revoke_reason FROM refresh_tokens t JOIN users u ON u.id = t.user_id
					WHERE u.phone = ? AND t.revoked_at IS NOT NULL""", String.class, phone))
			.containsExactly("REUSE_DETECTED");
	}

	@Test
	void aDisabledAdminLosesAdminAccessAtOnce() throws Exception {
		String phone = register(phone(), null);
		Session admin = adminSession(phone);
		as(admin, "/api/v1/admin/orders").andExpect(status().isOk());

		jdbc.update("UPDATE users SET enabled = false WHERE phone = ?", phone);
		as(admin, "/api/v1/admin/orders").andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
		as(admin, "/api/v1/admin/dashboard/summary").andExpect(status().isUnauthorized());
	}

	@Test
	void aDemotedAdminActsAsACustomerFromTheNextRequest() throws Exception {
		String phone = register(phone(), null);
		Session admin = adminSession(phone);
		as(admin, "/api/v1/admin/orders").andExpect(status().isOk());

		jdbc.update("UPDATE users SET role = 'CUSTOMER' WHERE phone = ?", phone);
		// The token still says ADMIN; the database decides.
		as(admin, "/api/v1/admin/orders").andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("FORBIDDEN"));
		as(admin, "/api/v1/orders").andExpect(status().isOk());
	}

	@Test
	void aPromotedCustomerGetsTheNewRoleWithoutSigningInAgain() throws Exception {
		String phone = register(phone(), null);
		Session session = login(phone, PASSWORD);
		as(session, "/api/v1/admin/orders").andExpect(status().isForbidden());

		jdbc.update("UPDATE users SET role = 'ADMIN' WHERE phone = ?", phone);
		as(session, "/api/v1/admin/orders").andExpect(status().isOk());
	}

	/** A signed-in ADMIN: a registered customer promoted before logging in. */
	private Session adminSession(String phone) throws Exception {
		jdbc.update("UPDATE users SET role = 'ADMIN' WHERE phone = ?", phone);
		return login(phone, PASSWORD);
	}

	private ResultActions as(Session session, String path) throws Exception {
		return mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, session.bearer()));
	}

}
