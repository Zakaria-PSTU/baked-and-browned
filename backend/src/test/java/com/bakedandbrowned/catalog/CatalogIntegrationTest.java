package com.bakedandbrowned.catalog;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;

import com.bakedandbrowned.IntegrationTest;

/**
 * Shared setup for catalog tests: catalog, users and audit rows are truncated before and after
 * each test. Data is inserted with SQL so each test states exactly what exists; admin calls use
 * real signed access tokens for real user rows (the audit actor is a foreign key).
 */
@IntegrationTest
abstract class CatalogIntegrationTest {

	private static final AtomicInteger SEQUENCE = new AtomicInteger();

	@Autowired
	protected MockMvc mockMvc;

	@Autowired
	protected JdbcTemplate jdbc;

	@Autowired
	private JwtEncoder jwtEncoder;

	@BeforeEach
	@AfterEach
	void resetCatalog() {
		jdbc.execute("TRUNCATE categories, customization_groups, users, audit_events CASCADE");
	}

	protected UUID category(String name, String slug, int sortOrder, boolean active) {
		return jdbc.queryForObject(
				"INSERT INTO categories (name, slug, sort_order, active) VALUES (?, ?, ?, ?) RETURNING id", UUID.class,
				name, slug, sortOrder, active);
	}

	protected UUID product(UUID categoryId, String name, String slug, String price, String status) {
		return jdbc.queryForObject("""
				INSERT INTO products (category_id, name, slug, base_price, status)
				VALUES (?, ?, ?, ?, ?::product_status) RETURNING id""", UUID.class, categoryId, name, slug,
				new BigDecimal(price), status);
	}

	protected UUID group(String code, String name, String type, int min, int max) {
		return jdbc.queryForObject("""
				INSERT INTO customization_groups (code, name, selection_type, min_selections, max_selections)
				VALUES (?, ?, ?, ?, ?) RETURNING id""", UUID.class, code, name, type, min, max);
	}

	protected UUID option(UUID groupId, String name, String delta, boolean active) {
		return jdbc.queryForObject("""
				INSERT INTO customization_options (group_id, name, price_delta, active)
				VALUES (?, ?, ?, ?) RETURNING id""", UUID.class, groupId, name, new BigDecimal(delta), active);
	}

	protected void assign(UUID productId, UUID groupId, boolean required, Integer textMaxLength, UUID... options) {
		jdbc.update("""
				INSERT INTO product_customization_groups (product_id, group_id, required, text_max_length)
				VALUES (?, ?, ?, ?)""", productId, groupId, required, textMaxLength);
		for (UUID option : options) {
			jdbc.update("INSERT INTO product_customization_options (product_id, group_id, option_id) VALUES (?, ?, ?)",
					productId, groupId, option);
		}
	}

	/** A bearer header for a new user row with this role. */
	protected String bearer(String role) {
		UUID id = jdbc.queryForObject("""
				INSERT INTO users (full_name, phone, password_hash, role)
				VALUES ('Test ' || ?, ?, 'unused', ?::user_role) RETURNING id""", UUID.class, role,
				"+88019%08d".formatted(SEQUENCE.incrementAndGet()), role);
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.issuer("https://api.bakedandbrowned.com.bd")
			.audience(List.of("baked-and-browned-web"))
			.subject(id.toString())
			.issuedAt(now)
			.expiresAt(now.plus(Duration.ofMinutes(15)))
			.claim("roles", List.of(role))
			.build();
		JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId("bb-access-1").build();
		return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
	}

	protected UUID userIdOf(String bearer) {
		String payload = new String(java.util.Base64.getUrlDecoder().decode(bearer.split("\\.")[1]),
				java.nio.charset.StandardCharsets.UTF_8);
		return UUID.fromString(payload.replaceAll(".*\"sub\":\"([^\"]+)\".*", "$1"));
	}

}
