package com.bakedandbrowned.identity.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import com.bakedandbrowned.common.security.JwtConfig;
import com.bakedandbrowned.common.security.JwtSigningKey;
import com.bakedandbrowned.common.security.SecurityProperties;
import com.bakedandbrowned.identity.entity.UserAccount;

/**
 * Issues short-lived RS256 access tokens: {@code sub} = user id, {@code roles} = [role], plus
 * {@code iss}, {@code aud}, {@code iat}, {@code exp} and a unique {@code jti}. No personal data
 * (name, phone, email) goes into the token.
 */
@Component
public class AccessTokenIssuer {

	private final JwtEncoder encoder;

	private final Clock clock;

	private final SecurityProperties.Jwt properties;

	private final String keyId;

	public AccessTokenIssuer(JwtEncoder encoder, Clock clock, SecurityProperties properties, JwtSigningKey key) {
		this.encoder = encoder;
		this.clock = clock;
		this.properties = properties.jwt();
		this.keyId = key.keyId();
	}

	public String issue(UserAccount user) {
		Instant now = clock.instant();
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.issuer(properties.issuer())
			.audience(List.of(properties.audience()))
			.subject(user.getId().toString())
			.issuedAt(now)
			.expiresAt(now.plus(properties.accessTokenTtl()))
			.id(UUID.randomUUID().toString())
			.claim(JwtConfig.ROLES_CLAIM,List.of(user.getRole().name()))
			.build();
		JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).build();
		return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
	}

	public long ttlSeconds() {
		return properties.accessTokenTtl().toSeconds();
	}

}
