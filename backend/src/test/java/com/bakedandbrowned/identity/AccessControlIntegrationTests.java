package com.bakedandbrowned.identity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
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
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.bakedandbrowned.common.security.JwtSigningKey;
import com.bakedandbrowned.common.value.PhoneNumber;
import com.bakedandbrowned.identity.service.AdminProvisioningService;

/** Default deny, role checks and access-token validation with real tokens. */
class AccessControlIntegrationTests extends IdentityIntegrationTest {

	@Autowired
	private JwtEncoder jwtEncoder;

	@Autowired
	private AdminProvisioningService adminProvisioning;

	@Test
	void publicCatalogPathsAreNotBlockedBySecurity() throws Exception {
		// Anonymous requests reach MVC: whatever it answers (200, or 404 for paths without a
		// controller yet), security did not stop them with 401/403.
		for (String path : List.of("/api/v1/categories", "/api/v1/products", "/api/v1/products/fudge-brownie",
				"/api/v1/delivery-slots", "/api/v1/delivery-zones")) {
			mockMvc.perform(get(path)).andExpect(notBlockedBySecurity());
		}
		mockMvc.perform(post("/api/v1/payments/webhooks/sslcommerz")).andExpect(notBlockedBySecurity());
		mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
	}

	@Test
	void protectedPathsRequireAuthentication() throws Exception {
		for (String path : List.of("/api/v1/orders", "/api/v1/admin/orders", "/actuator/env", "/actuator/info")) {
			mockMvc.perform(get(path))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		}
		mockMvc.perform(post("/api/v1/checkout/quote")).andExpect(status().isUnauthorized());
	}

	@Test
	void adminPathsRequireTheAdminRole() throws Exception {
		Session customer = login(register(phone(), null), PASSWORD);
		mockMvc.perform(get("/api/v1/admin/orders").header(HttpHeaders.AUTHORIZATION, customer.bearer()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("FORBIDDEN"));

		String adminPhone = phone();
		adminProvisioning.provisionFirstAdmin("Store Admin", PhoneNumber.of(adminPhone), null, PASSWORD);
		Session admin = login(adminPhone, PASSWORD);
		mockMvc.perform(get("/api/v1/admin/orders").header(HttpHeaders.AUTHORIZATION, admin.bearer()))
			.andExpect(status().isOk());
	}

	@Test
	void tamperedForeignExpiredOrMisaddressedTokensAreRejected() throws Exception {
		Session session = login(register(phone(), null), PASSWORD);
		String[] parts = session.accessToken().split("\\.");
		String tampered = parts[0] + "." + parts[1] + "x." + parts[2];

		JwtSigningKey otherKey = JwtSigningKey.generateEphemeral("bb-access-1");
		JwtEncoder foreign = NimbusJwtEncoder.withKeyPair(otherKey.publicKey(), otherKey.privateKey())
			.jwkPostProcessor(b -> b.keyID("bb-access-1"))
			.build();
		Instant now = Instant.now();

		for (String token : List.of(tampered, encode(foreign, valid(now)),
				encode(jwtEncoder, valid(now).expiresAt(now.minus(Duration.ofMinutes(5))).issuedAt(now.minus(Duration.ofMinutes(20)))),
				encode(jwtEncoder, valid(now).audience(List.of("someone-else"))),
				encode(jwtEncoder, valid(now).issuer("https://evil.example")),
				encode(jwtEncoder, valid(now).claim("roles", List.of())))) {
			mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_TOKEN"))
				// Parser and validator messages are not echoed.
				.andExpect(jsonPath("$.detail").value("The access token is invalid or has expired."));
		}
		// A well-formed token naming no account is refused the same way (ADR-0028).
		mockMvc.perform(get("/api/v1/auth/me")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + encode(jwtEncoder, valid(now))))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
		// The control: a well-formed token from the application key, for an existing ADMIN, is accepted.
		UUID adminId = jdbc.queryForObject("""
				INSERT INTO users (full_name, phone, password_hash, role)
				VALUES ('Token Admin', ?, 'unused', 'ADMIN') RETURNING id""", UUID.class, phone());
		mockMvc.perform(get("/api/v1/admin/orders")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + encode(jwtEncoder,
					valid(now).subject(adminId.toString()).claim("roles", List.of("ADMIN")))))
			.andExpect(status().isOk());
	}

	@Test
	void theTokenVersionClaimMustBePresentWellFormedAndCurrent() throws Exception {
		// ADR-0031: fail closed on a missing or malformed "ver", and on any version but the account's.
		UUID adminId = jdbc.queryForObject("""
				INSERT INTO users (full_name, phone, password_hash, role)
				VALUES ('Version Admin', ?, 'unused', 'ADMIN') RETURNING id""", UUID.class, phone());
		Instant now = Instant.now();
		java.util.function.Function<JwtClaimsSet.Builder, String> asAdmin = claims -> "Bearer "
				+ encode(jwtEncoder, claims.subject(adminId.toString()).claim("roles", List.of("ADMIN")));

		for (JwtClaimsSet.Builder bad : List.of(valid(now).claims(c -> c.remove("ver")), valid(now).claim("ver", "0"),
				valid(now).claim("ver", -1), valid(now).claim("ver", 0.5), valid(now).claim("ver", true),
				valid(now).claim("ver", 1))) {
			mockMvc.perform(get("/api/v1/admin/orders").header(HttpHeaders.AUTHORIZATION, asAdmin.apply(bad)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_TOKEN"))
				.andExpect(jsonPath("$.detail").value("The access token is invalid or has expired."));
		}
		mockMvc.perform(get("/api/v1/admin/orders").header(HttpHeaders.AUTHORIZATION, asAdmin.apply(valid(now))))
			.andExpect(status().isOk());

		// Real sign-ins carry the account's current version.
		Session session = login(register(phone(), null), PASSWORD);
		String payload = new String(java.util.Base64.getUrlDecoder().decode(session.accessToken().split("\\.")[1]),
				java.nio.charset.StandardCharsets.UTF_8);
		org.assertj.core.api.Assertions.assertThat(payload).contains("\"ver\":0");
	}

	@Test
	void accessTokensCarryNoPersonalData() throws Exception {
		String phone = phone();
		Session session = login(register(phone, "afsana@example.com"), PASSWORD);
		String payload = new String(java.util.Base64.getUrlDecoder().decode(session.accessToken().split("\\.")[1]),
				java.nio.charset.StandardCharsets.UTF_8);
		org.assertj.core.api.Assertions.assertThat(payload)
			.doesNotContain(phone)
			.doesNotContain("afsana")
			.doesNotContain("Nusrat")
			.contains("\"roles\":[\"CUSTOMER\"]");
		String header = new String(java.util.Base64.getUrlDecoder().decode(session.accessToken().split("\\.")[0]),
				java.nio.charset.StandardCharsets.UTF_8);
		org.assertj.core.api.Assertions.assertThat(header).contains("\"kid\":\"bb-access-1\"", "\"alg\":\"RS256\"");
	}

	private static org.springframework.test.web.servlet.ResultMatcher notBlockedBySecurity() {
		return result -> org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus())
			.as("%s must not be blocked by security", result.getRequest().getRequestURI())
			.isNotIn(401, 403);
	}

	private static JwtClaimsSet.Builder valid(Instant now) {
		return JwtClaimsSet.builder()
			.issuer("https://api.bakedandbrowned.com.bd")
			.audience(List.of("baked-and-browned-web"))
			.subject(UUID.randomUUID().toString())
			.issuedAt(now)
			.expiresAt(now.plus(Duration.ofMinutes(15)))
			.claim("roles", List.of("CUSTOMER"))
			.claim("ver", 0);
	}

	private static String encode(JwtEncoder encoder, JwtClaimsSet.Builder claims) {
		JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId("bb-access-1").build();
		return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
	}

}
