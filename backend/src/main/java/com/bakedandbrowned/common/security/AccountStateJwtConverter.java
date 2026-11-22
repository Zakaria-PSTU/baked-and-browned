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
 * <p>
 * The token's {@code ver} must equal the account's {@code token_version} (ADR-0031), which moves on
 * whenever the account is disabled or re-enabled. So a token issued before a disable stays refused
 * after a re-enable. A token without {@code ver}, or with anything but a non-negative whole number,
 * is refused the same way (fail closed).
 * <p>
 * The token's {@code sid} names the session (refresh family) it was issued for, and the session must
 * still be live (ADR-0032): at least one of its refresh tokens is not revoked. Logout, reuse
 * detection, a disable and the purge of an expired session all leave a family with none, so they
 * stop its access tokens on the next request. The check is part of the same single statement. A
 * token without a canonical-UUID {@code sid}, or naming a session of another account, is refused.
 */
final class AccountStateJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {

	private final JdbcTemplate jdbc;

	AccountStateJwtConverter(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public AbstractAuthenticationToken convert(Jwt jwt) {
		UUID userId = userId(jwt);
		long tokenVersion = tokenVersion(jwt);
		UUID sessionId = sessionId(jwt);
		// The live token is found by session alone (LIMIT 1) and its owner compared afterwards. Putting
		// "r.user_id = users.id" into the subquery makes the planner AND two indexes and read the
		// whole of a long session's entries; this form is a single probe of idx_refresh_family_active
		// (V17), measured at about 0.1 ms warm even for a 2,900-token session (ADR-0032, amendment).
		List<Map<String, Object>> found = jdbc.queryForList("""
				SELECT role::text AS role, enabled, token_version,
				       COALESCE((SELECT r.user_id = users.id FROM refresh_tokens r
				                 WHERE r.family_id = ? AND r.revoked_at IS NULL LIMIT 1), false) AS session_live
				FROM users WHERE id = ?""", sessionId, userId);
		if (found.isEmpty() || !Boolean.TRUE.equals(found.get(0).get("enabled"))
				|| !(found.get(0).get("token_version") instanceof Integer current) || current != tokenVersion
				|| !Boolean.TRUE.equals(found.get(0).get("session_live"))) {
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

	/** The {@code ver} claim as a whole number of at least 0; anything else is refused. */
	private static long tokenVersion(Jwt jwt) {
		Object claim = jwt.getClaims().get(JwtConfig.VERSION_CLAIM);
		if ((claim instanceof Long || claim instanceof Integer) && ((Number) claim).longValue() >= 0) {
			return ((Number) claim).longValue();
		}
		throw refused();
	}

	/** The {@code sid} claim as a canonical UUID string; anything else is refused. */
	private static UUID sessionId(Jwt jwt) {
		Object claim = jwt.getClaims().get(JwtConfig.SESSION_CLAIM);
		if (claim instanceof String text) {
			try {
				UUID id = UUID.fromString(text);
				if (id.toString().equals(text)) {
					return id;
				}
			}
			catch (IllegalArgumentException ex) {
				// falls through to the refusal
			}
		}
		throw refused();
	}

	/** Generic on purpose: the response never says whether the account exists or is disabled. */
	private static InvalidBearerTokenException refused() {
		return new InvalidBearerTokenException("The access token is invalid or has expired.");
	}

}
