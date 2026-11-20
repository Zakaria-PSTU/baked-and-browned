package com.bakedandbrowned.common.security;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.core.convert.converter.Converter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Turns a verified access token into an authentication from the account as it is now (ADR-0028):
 * the token names the user, the database decides whether they may act and with which role.
 * <p>
 * A token whose account no longer exists or is disabled is refused as an invalid token (401
 * {@code INVALID_TOKEN}), so the web app tries to refresh, which is refused for a disabled account
 * as well, and signs the user out. The role comes from {@code users.role}, not from the token's
 * {@code roles} claim: a demoted ADMIN acts as a CUSTOMER from their next request, and is refused
 * {@code /admin/**} (403). One primary-key lookup per request that presents a token; requests
 * without one are not looked up.
 */
final class AccountStateJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {

	private final JdbcTemplate jdbc;

	AccountStateJwtConverter(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public AbstractAuthenticationToken convert(Jwt jwt) {
		UUID userId = userId(jwt);
		List<Map<String, Object>> found = jdbc.queryForList("SELECT role::text AS role, enabled FROM users WHERE id = ?",
				userId);
		if (found.isEmpty() || !Boolean.TRUE.equals(found.get(0).get("enabled"))) {
			throw refused();
		}
		String role = (String) found.get(0).get("role");
		return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_" + role)), jwt.getSubject());
	}

	private static UUID userId(Jwt jwt) {
		try {
			return UUID.fromString(jwt.getSubject());
		}
		catch (IllegalArgumentException | NullPointerException ex) {
			throw refused();
		}
	}

	/** Generic on purpose: the response never says whether the account exists or is disabled. */
	private static InvalidBearerTokenException refused() {
		return new InvalidBearerTokenException("The access token is invalid or has expired.");
	}

}
