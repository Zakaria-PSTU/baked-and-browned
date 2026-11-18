package com.bakedandbrowned.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import jakarta.servlet.http.Cookie;

/** Contract tag {@code Auth} end to end, against the real security chain and PostgreSQL. */
class AuthApiIntegrationTests extends IdentityIntegrationTest {

	// ------------------------------------------------------------------ register

	@Test
	void registrationCreatesACustomerWithAnArgon2idHash() throws Exception {
		String phone = phone();
		mockMvc.perform(post("/api/v1/auth/register").with(fromNewIp())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"fullName":"Nusrat Jahan","phone":"%s","email":"Nusrat@Example.com","password":"%s","role":"ADMIN"}"""
				.formatted(phone, PASSWORD)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.id").isNotEmpty())
			.andExpect(jsonPath("$.fullName").value("Nusrat Jahan"))
			.andExpect(jsonPath("$.phone").value(phone))
			.andExpect(jsonPath("$.email").value("nusrat@example.com"))
			// A client-supplied role is ignored: registration only ever creates customers.
			.andExpect(jsonPath("$.role").value("CUSTOMER"))
			.andExpect(jsonPath("$.password").doesNotExist())
			.andExpect(jsonPath("$.passwordHash").doesNotExist());

		Map<String, Object> row = jdbc.queryForMap("SELECT role::text AS role, password_hash FROM users WHERE phone = ?",
				phone);
		assertThat(row.get("role")).isEqualTo("CUSTOMER");
		assertThat((String) row.get("password_hash")).startsWith("$argon2id$").doesNotContain(PASSWORD);
	}

	@Test
	void duplicatePhoneOrCaseInsensitiveEmailIsConflict() throws Exception {
		String phone = register(phone(), "rina@example.com");

		registerExpecting(phone, "other@example.com", 409).andExpect(jsonPath("$.code").value("ACCOUNT_EXISTS"));
		registerExpecting(phone(), "RINA@example.COM", 409).andExpect(jsonPath("$.code").value("ACCOUNT_EXISTS"));
	}

	@Test
	void invalidRegistrationsAre422WithoutEchoingThePassword() throws Exception {
		registerExpecting("01712345678", null, 422).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		String shortPassword = "short-pass1";
		MvcResult result = mockMvc
			.perform(post("/api/v1/auth/register").with(fromNewIp())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"fullName":"Nusrat Jahan","phone":"%s","password":"%s"}""".formatted(phone(), shortPassword)))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("password")))
			.andReturn();
		assertThat(result.getResponse().getContentAsString()).doesNotContain(shortPassword);
	}

	// ------------------------------------------------------------------ login

	@Test
	void loginByPhoneOrEmailIssuesTokensAndSecureCookies() throws Exception {
		String phone = register(phone(), "karim@example.com");

		MvcResult result = mockMvc
			.perform(post("/api/v1/auth/login").with(fromNewIp())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"identifier":"%s","password":"%s"}""".formatted(phone, PASSWORD)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.tokenType").value("Bearer"))
			.andExpect(jsonPath("$.expiresIn").value(900))
			.andExpect(jsonPath("$.user.phone").value(phone))
			.andExpect(jsonPath("$.user.role").value("CUSTOMER"))
			.andReturn();

		assertThat(setCookieHeader(result, "refresh_token")).contains("HttpOnly", "Secure", "SameSite=Strict",
				"Path=/api/v1/auth", "Max-Age=1209600");
		assertThat(setCookieHeader(result, "XSRF-TOKEN")).contains("Secure", "SameSite=Strict", "Path=/")
			.doesNotContain("HttpOnly");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens", Integer.class)).isEqualTo(1);
		// Only the hash is stored.
		String refresh = setCookieValue(result, "refresh_token");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE token_hash = ?", Integer.class, refresh))
			.isZero();

		Session byEmail = login("KARIM@example.com", PASSWORD);
		assertThat(byEmail.body().get("user").get("phone").asString()).isEqualTo(phone);
	}

	@Test
	void everyLoginFailureIsTheSame401() throws Exception {
		String phone = register(phone(), null);
		// Correct password, but the account is disabled.
		String disabledPhone = register(phone(), null);
		jdbc.update("UPDATE users SET enabled = false WHERE phone = ?", disabledPhone);

		for (String[] attempt : new String[][] { { phone, "wrong password here" }, { phone(), PASSWORD },
				{ "nobody@example.com", PASSWORD }, { "not-an-identifier", PASSWORD }, { disabledPhone, PASSWORD },
				{ phone, "x".repeat(10_000) } }) {
			mockMvc
				.perform(post("/api/v1/auth/login").with(fromNewIp())
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{"identifier":"%s","password":"%s"}""".formatted(attempt[0], attempt[1])))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
				.andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
		}
	}

	@Test
	void loginIsRateLimitedPerClientIp() throws Exception {
		String ip = newIp();
		String body = """
				{"identifier":"%s","password":"wrong password here"}""".formatted(phone());
		for (int i = 0; i < 10; i++) {
			mockMvc.perform(post("/api/v1/auth/login").with(fromIp(ip)).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isUnauthorized());
		}
		mockMvc.perform(post("/api/v1/auth/login").with(fromIp(ip)).contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isTooManyRequests())
			.andExpect(header().exists(HttpHeaders.RETRY_AFTER))
			.andExpect(jsonPath("$.code").value("RATE_LIMITED"));
		// Another client is unaffected.
		mockMvc.perform(post("/api/v1/auth/login").with(fromNewIp()).contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isUnauthorized());
	}

	// ------------------------------------------------------------------ me

	@Test
	void meReturnsTheBearerUser() throws Exception {
		String phone = register(phone(), null);
		Session session = login(phone, PASSWORD);

		mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, session.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.phone").value(phone))
			.andExpect(jsonPath("$.email").isEmpty());

		mockMvc.perform(get("/api/v1/auth/me"))
			.andExpect(status().isUnauthorized())
			.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	void disabledAccountsLoseMeImmediately() throws Exception {
		String phone = register(phone(), null);
		Session session = login(phone, PASSWORD);
		jdbc.update("UPDATE users SET enabled = false WHERE phone = ?", phone);

		mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, session.bearer()))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(refreshRequest(session)).andExpect(status().isUnauthorized());
		assertThat(jdbc.queryForList("SELECT revoke_reason FROM refresh_tokens", String.class))
			.containsOnly("ACCOUNT_DISABLED");
	}

	// ------------------------------------------------------------------ refresh

	@Test
	void refreshRotatesTheTokenWithinItsFamily() throws Exception {
		Session first = login(register(phone(), null), PASSWORD);

		MvcResult result = mockMvc.perform(refreshRequest(first)
			// A stale access token on a public auth endpoint is ignored, not a 401.
			.header(HttpHeaders.AUTHORIZATION, "Bearer expired.or.garbage"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accessToken").isNotEmpty())
			.andReturn();
		Session second = Session.from(result, json);

		assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
		assertThat(second.xsrfToken()).isNotEqualTo(first.xsrfToken());
		Map<String, Object> spent = jdbc.queryForMap("""
				SELECT t.used_at, t.replaced_by_id, t.family_id, n.family_id AS successor_family, n.used_at AS successor_used
				FROM refresh_tokens t JOIN refresh_tokens n ON n.id = t.replaced_by_id""");
		assertThat(spent.get("used_at")).isNotNull();
		assertThat(spent.get("successor_family")).isEqualTo(spent.get("family_id"));
		assertThat(spent.get("successor_used")).isNull();

		mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, second.bearer()))
			.andExpect(status().isOk());
	}

	@Test
	void reusingARotatedTokenRevokesTheWholeFamily() throws Exception {
		String phone = register(phone(), null);
		Session stolen = login(phone, PASSWORD);
		Session otherDevice = login(phone, PASSWORD);
		Session legitimate = Session.from(mockMvc.perform(refreshRequest(stolen)).andExpect(status().isOk()).andReturn(),
				json);

		MvcResult reuse = mockMvc.perform(refreshRequest(stolen))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"))
			.andReturn();
		assertThat(setCookieHeader(reuse, "refresh_token")).contains("Max-Age=0");

		// The successor the legitimate client holds is dead too; the other login's family is not.
		mockMvc.perform(refreshRequest(legitimate)).andExpect(status().isUnauthorized());
		mockMvc.perform(refreshRequest(otherDevice)).andExpect(status().isOk());
		assertThat(jdbc.queryForObject(
				"SELECT count(DISTINCT family_id) FROM refresh_tokens WHERE revoke_reason = 'REUSE_DETECTED'",
				Integer.class))
			.isEqualTo(1);
	}

	@Test
	void refreshRequiresAllowedOriginAndMatchingCsrfToken() throws Exception {
		Session session = login(register(phone(), null), PASSWORD);
		Cookie refresh = new Cookie("refresh_token", session.refreshToken());
		Cookie xsrf = new Cookie("XSRF-TOKEN", session.xsrfToken());

		// No Origin.
		expectCsrfRejected(post("/api/v1/auth/refresh").with(fromNewIp())
			.header("X-CSRF-Token", session.xsrfToken())
			.cookie(refresh, xsrf));
		// Foreign Origin.
		expectCsrfRejected(post("/api/v1/auth/refresh").with(fromNewIp())
			.header("Origin", "https://evil.example")
			.header("X-CSRF-Token", session.xsrfToken())
			.cookie(refresh, xsrf));
		// No header.
		expectCsrfRejected(post("/api/v1/auth/refresh").with(fromNewIp()).header("Origin", ORIGIN).cookie(refresh, xsrf));
		// Header does not match the cookie.
		expectCsrfRejected(post("/api/v1/auth/refresh").with(fromNewIp())
			.header("Origin", ORIGIN)
			.header("X-CSRF-Token", "attacker-guess")
			.cookie(refresh, xsrf));

		// None of the rejected attempts spent the token.
		mockMvc.perform(refreshRequest(session)).andExpect(status().isOk());
	}

	@Test
	void refreshWithoutOrWithAnUnknownCookieIs401() throws Exception {
		mockMvc.perform(post("/api/v1/auth/refresh").with(fromNewIp())
			.header("Origin", ORIGIN)
			.header("X-CSRF-Token", "t")
			.cookie(new Cookie("XSRF-TOKEN", "t")))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/v1/auth/refresh").with(fromNewIp())
			.header("Origin", ORIGIN)
			.header("X-CSRF-Token", "t")
			.cookie(new Cookie("XSRF-TOKEN", "t"), new Cookie("refresh_token", "forged")))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
	}

	@Test
	void expiredRefreshTokensAreRejected() throws Exception {
		Session session = login(register(phone(), null), PASSWORD);
		jdbc.update("UPDATE refresh_tokens SET expires_at = now() - interval '1 second'");

		mockMvc.perform(refreshRequest(session)).andExpect(status().isUnauthorized());
	}

	// ------------------------------------------------------------------ logout

	@Test
	void logoutRevokesTheFamilyAndClearsCookies() throws Exception {
		Session session = login(register(phone(), null), PASSWORD);
		Session rotated = Session.from(mockMvc.perform(refreshRequest(session)).andReturn(), json);

		MvcResult result = mockMvc.perform(post("/api/v1/auth/logout")
			.header("Origin", ORIGIN)
			.header("X-CSRF-Token", rotated.xsrfToken())
			.cookie(new Cookie("refresh_token", rotated.refreshToken()), new Cookie("XSRF-TOKEN", rotated.xsrfToken())))
			.andExpect(status().isNoContent())
			.andReturn();

		assertThat(setCookieHeader(result, "refresh_token")).contains("Max-Age=0");
		assertThat(setCookieHeader(result, "XSRF-TOKEN")).contains("Max-Age=0");
		assertThat(jdbc.queryForList("SELECT revoke_reason FROM refresh_tokens", String.class)).hasSize(2)
			.containsOnly("LOGOUT");
		mockMvc.perform(refreshRequest(rotated)).andExpect(status().isUnauthorized());
	}

	@Test
	void logoutIsCsrfProtected() throws Exception {
		Session session = login(register(phone(), null), PASSWORD);

		expectCsrfRejected(post("/api/v1/auth/logout").header("Origin", "https://evil.example")
			.cookie(new Cookie("refresh_token", session.refreshToken()), new Cookie("XSRF-TOKEN", session.xsrfToken())));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE revoked_at IS NOT NULL", Integer.class))
			.isZero();
	}

	// ------------------------------------------------------------------ helpers

	private void expectCsrfRejected(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
			throws Exception {
		mockMvc.perform(request)
			.andExpect(status().isForbidden())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
	}

	private org.springframework.test.web.servlet.ResultActions registerExpecting(String phone, String email, int status)
			throws Exception {
		String emailJson = email == null ? "" : ",\"email\":\"" + email + "\"";
		return mockMvc
			.perform(post("/api/v1/auth/register").with(fromNewIp())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"fullName":"Nusrat Jahan","phone":"%s","password":"%s"%s}""".formatted(phone, PASSWORD, emailJson)))
			.andExpect(status().is(status));
	}

}
