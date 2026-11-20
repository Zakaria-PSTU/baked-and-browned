package com.bakedandbrowned.common.security;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.security.*}. Defaults live in {@code application.yaml}; secrets (the JWT private key)
 * come only from the environment or a secret store.
 *
 * @param jwt access-token signing and validation
 * @param sessions per-role refresh-session lifetimes (ADR-0025)
 * @param secureCookies {@code Secure} flag on auth cookies; only ever false for plain-HTTP tests
 * @param allowedOrigins exact {@code Origin} values accepted on cookie-authenticated endpoints
 * (refresh, logout)
 */
@ConfigurationProperties("app.security")
public record SecurityProperties(Jwt jwt, Sessions sessions, boolean secureCookies, List<String> allowedOrigins) {

	public SecurityProperties {
		Objects.requireNonNull(jwt, "app.security.jwt must be configured");
		Objects.requireNonNull(sessions, "app.security.sessions must be configured");
		allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
	}

	/** {@code app.security.sessions.*}: the refresh-session lifetimes of each role. */
	public record Sessions(SessionLifetime customer, SessionLifetime admin) {

		public Sessions {
			Objects.requireNonNull(customer, "app.security.sessions.customer must be configured");
			Objects.requireNonNull(admin, "app.security.sessions.admin must be configured");
		}

	}

	/**
	 * One role's refresh-session lifetime. Each refresh extends the session by {@code idle}, but
	 * never past {@code absolute} after the login that started it.
	 *
	 * @param idle how long a session survives without a refresh
	 * @param absolute the session's maximum age, however often it is refreshed
	 */
	public record SessionLifetime(Duration idle, Duration absolute) {

		public SessionLifetime {
			Objects.requireNonNull(idle, "session idle lifetime must be configured");
			Objects.requireNonNull(absolute, "session absolute lifetime must be configured");
			if (idle.isNegative() || idle.isZero() || absolute.isNegative() || absolute.isZero()) {
				throw new IllegalArgumentException("session lifetimes must be positive: idle=" + idle + ", absolute="
						+ absolute);
			}
			if (idle.compareTo(absolute) > 0) {
				throw new IllegalArgumentException("session idle lifetime " + idle
						+ " must not exceed the absolute lifetime " + absolute);
			}
		}

	}

	/**
	 * @param issuer {@code iss} written and required
	 * @param audience {@code aud} written and required
	 * @param accessTokenTtl access-token lifetime (architecture: 15 minutes)
	 * @param keyId {@code kid} header, so keys can be rotated later
	 * @param privateKey PKCS#8 PEM RSA private key (2048 bits or more); the public key is derived
	 * from it
	 * @param allowEphemeralKey when no {@code privateKey} is set, generate a throwaway key at startup
	 * instead of failing. Tests and local development only: tokens die with the process.
	 */
	public record Jwt(String issuer, String audience, Duration accessTokenTtl, String keyId,
			@Nullable String privateKey, boolean allowEphemeralKey) {

		public Jwt {
			Objects.requireNonNull(issuer, "app.security.jwt.issuer must be configured");
			Objects.requireNonNull(audience, "app.security.jwt.audience must be configured");
			Objects.requireNonNull(accessTokenTtl, "app.security.jwt.access-token-ttl must be configured");
			Objects.requireNonNull(keyId, "app.security.jwt.key-id must be configured");
		}

		/** Never print the key. */
		@Override
		public String toString() {
			return "Jwt[issuer=" + issuer + ", audience=" + audience + ", accessTokenTtl=" + accessTokenTtl + ", keyId="
					+ keyId + ", privateKey=" + (privateKey == null ? "<unset>" : "<redacted>") + ", allowEphemeralKey="
					+ allowEphemeralKey + "]";
		}

	}

}
