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
	void reEnablingAnAccountRestoresItsToken() throws Exception {
		String phone = register(phone(), null);
		Session session = login(phone, PASSWORD);
		jdbc.update("UPDATE users SET enabled = false WHERE phone = ?", phone);
		as(session, "/api/v1/orders").andExpect(status().isUnauthorized());

		jdbc.update("UPDATE users SET enabled = true WHERE phone = ?", phone);
		as(session, "/api/v1/orders").andExpect(status().isOk());
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
