package com.bakedandbrowned.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import jakarta.servlet.http.Cookie;

/**
 * ADR-0032: an access token is honoured only while the session it was issued for is live. Logout,
 * reuse detection and the end of a session stop its access tokens on the next request, not up to
 * 15 minutes later. Every doubt about the token's {@code sid} gives the generic 401.
 */
class SessionBoundAccessTokenIntegrationTests extends IdentityIntegrationTest {

	private static final String PATH = "/api/v1/orders";

	@Autowired
	private JwtEncoder jwtEncoder;

	@Test
	void logoutEndsTheAccessTokensOfThatSessionOnly() throws Exception {
		String phone = register(phone(), null);
		Session first = login(phone, PASSWORD);
		Session rotated = Session.from(mockMvc.perform(refreshRequest(first)).andReturn(), json);
		Session otherDevice = login(phone, PASSWORD);
		for (Session session : List.of(first, rotated, otherDevice)) {
			call(session.accessToken()).andExpect(status().isOk());
		}

		mockMvc.perform(post("/api/v1/auth/logout")
			.header("Origin", ORIGIN)
			.header("X-CSRF-Token", rotated.xsrfToken())
			.cookie(new Cookie("refresh_token", rotated.refreshToken()), new Cookie("XSRF-TOKEN", rotated.xsrfToken())))
			.andExpect(status().isNoContent());

		// Both access tokens of the logged-out session stop at once; the refresh was already refused.
		for (Session session : List.of(first, rotated)) {
			call(session.accessToken()).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_TOKEN"))
				.andExpect(jsonPath("$.detail").value("The access token is invalid or has expired."));
		}
		// The same user's other session is a different family and keeps working.
		call(otherDevice.accessToken()).andExpect(status().isOk());
		mockMvc.perform(refreshRequest(otherDevice)).andExpect(status().isOk());
	}

	@Test
	void reuseDetectionEndsTheSessionsAccessTokens() throws Exception {
		Session first = login(register(phone(), null), PASSWORD);
		Session next = Session.from(mockMvc.perform(refreshRequest(first)).andReturn(), json);
		call(next.accessToken()).andExpect(status().isOk());

		// Replaying the spent refresh token ends the whole family, and with it every access token.
		mockMvc.perform(refreshRequest(first)).andExpect(status().isUnauthorized());

		call(first.accessToken()).andExpect(status().isUnauthorized());
		call(next.accessToken()).andExpect(status().isUnauthorized());
	}

	@Test
	void aSessionWhoseTokensAreGoneEndsItsAccessTokens() throws Exception {
		// What the expired-session purge leaves behind: no rows for the family.
		Session session = login(register(phone(), null), PASSWORD);
		call(session.accessToken()).andExpect(status().isOk());

		jdbc.update("DELETE FROM refresh_tokens");

		call(session.accessToken()).andExpect(status().isUnauthorized());
	}

	@Test
	void everyDoubtAboutTheSessionClaimGivesTheGeneric401() throws Exception {
		String phone = register(phone(), null);
		login(phone, PASSWORD);
		UUID userId = jdbc.queryForObject("SELECT id FROM users WHERE phone = ?", UUID.class, phone);
		UUID family = jdbc.queryForObject("SELECT family_id FROM refresh_tokens WHERE user_id = ?", UUID.class, userId);
		Session stranger = login(register(phone(), null), PASSWORD);
		UUID strangersFamily = jdbc.queryForObject(
				"SELECT family_id FROM refresh_tokens WHERE user_id <> ?", UUID.class, userId);
		assertThat(stranger.accessToken()).isNotBlank();

		// The control: a token for the user's own live family is accepted.
		call(token(userId, claims -> claims.claim("sid", family.toString()))).andExpect(status().isOk());

		List<String> refused = List.of(
				token(userId, claims -> claims.claims(c -> c.remove("sid"))),
				token(userId, claims -> claims.claim("sid", "not-a-uuid")),
				token(userId, claims -> claims.claim("sid", UUID.randomUUID().toString())),
				token(userId, claims -> claims.claim("sid", strangersFamily.toString())),
				token(userId, claims -> claims.claim("sid", 5)),
				token(userId, claims -> claims.claim("sid", "")));
		for (String bad : refused) {
			call(bad).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_TOKEN"))
				.andExpect(jsonPath("$.detail").value("The access token is invalid or has expired."));
		}
	}

	@Test
	void theSessionClaimIsTheFamilyAndStaysTheSameAcrossRotations() throws Exception {
		String phone = register(phone(), null);
		Session first = login(phone, PASSWORD);
		Session next = Session.from(mockMvc.perform(refreshRequest(first)).andReturn(), json);
		UUID family = jdbc.queryForObject("SELECT DISTINCT family_id FROM refresh_tokens", UUID.class);

		assertThat(payload(first.accessToken())).contains("\"sid\":\"" + family + "\"");
		assertThat(payload(next.accessToken())).contains("\"sid\":\"" + family + "\"");
		// No personal data joins the token (the session id is a random UUID).
		assertThat(payload(first.accessToken())).doesNotContain(phone);
	}

	// ------------------------------------------------------------------ helpers

	private org.springframework.test.web.servlet.ResultActions call(String accessToken) throws Exception {
		return mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken));
	}

	/** A token signed by the application's key for {@code userId} with the account's current version. */
	private String token(UUID userId, java.util.function.UnaryOperator<JwtClaimsSet.Builder> customize) {
		Instant now = Instant.now();
		JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
			.issuer("https://api.bakedandbrowned.com.bd")
			.audience(List.of("baked-and-browned-web"))
			.subject(userId.toString())
			.issuedAt(now)
			.expiresAt(now.plus(Duration.ofMinutes(15)))
			.claim("roles", List.of("CUSTOMER"))
			.claim("ver", 0);
		JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId("bb-access-1").build();
		return jwtEncoder.encode(JwtEncoderParameters.from(header, customize.apply(claims).build())).getTokenValue();
	}

	private static String payload(String accessToken) {
		return new String(Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]), StandardCharsets.UTF_8);
	}

}
