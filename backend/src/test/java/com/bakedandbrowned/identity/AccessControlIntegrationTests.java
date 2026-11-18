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
		// No catalog controllers yet: reaching MVC (404) instead of 401 proves the path is public.
		for (String path : List.of("/api/v1/categories", "/api/v1/products", "/api/v1/products/fudge-brownie",
				"/api/v1/delivery-slots", "/api/v1/delivery-zones")) {
			mockMvc.perform(get(path)).andExpect(status().isNotFound());
		}
		mockMvc.perform(post("/api/v1/payments/webhooks/sslcommerz")).andExpect(status().isNotFound());
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
		// Authorized; there is no admin controller yet, so MVC answers 404.
		mockMvc.perform(get("/api/v1/admin/orders").header(HttpHeaders.AUTHORIZATION, admin.bearer()))
			.andExpect(status().isNotFound());
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
		// The control: a well-formed token from the application key is accepted.
		mockMvc.perform(get("/api/v1/admin/orders")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + encode(jwtEncoder, valid(now).claim("roles", List.of("ADMIN")))))
			.andExpect(status().isNotFound());
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

	private static JwtClaimsSet.Builder valid(Instant now) {
		return JwtClaimsSet.builder()
			.issuer("https://api.bakedandbrowned.com.bd")
			.audience(List.of("baked-and-browned-web"))
			.subject(UUID.randomUUID().toString())
			.issuedAt(now)
			.expiresAt(now.plus(Duration.ofMinutes(15)))
			.claim("roles", List.of("CUSTOMER"));
	}

	private static String encode(JwtEncoder encoder, JwtClaimsSet.Builder claims) {
		JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId("bb-access-1").build();
		return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
	}

}
