package com.bakedandbrowned.common.security;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Access tokens are RS256 JWTs signed with our own key (architecture: asymmetric, {@code kid} in
 * the header, key from the secret store). Validation requires signature, issuer, audience, a
 * present and unexpired {@code exp}, and a non-empty {@code roles} claim.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SecurityProperties.class)
public class JwtConfig {

	/** Claim holding the user's roles, for example {@code ["CUSTOMER"]}. */
	public static final String ROLES_CLAIM = "roles";

	private static final Logger log = LoggerFactory.getLogger(JwtConfig.class);

	@Bean
	JwtSigningKey jwtSigningKey(SecurityProperties properties) {
		SecurityProperties.Jwt jwt = properties.jwt();
		if (jwt.privateKey() != null && !jwt.privateKey().isBlank()) {
			return JwtSigningKey.fromPem(jwt.keyId(), jwt.privateKey());
		}
		if (!jwt.allowEphemeralKey()) {
			throw new IllegalStateException("No JWT signing key: set APP_SECURITY_JWT_PRIVATE_KEY (PKCS#8 PEM RSA key). "
					+ "For tests or local development only, set app.security.jwt.allow-ephemeral-key=true.");
		}
		log.warn("Using an ephemeral JWT signing key: access tokens are invalidated on restart. Never use this in production.");
		return JwtSigningKey.generateEphemeral(jwt.keyId());
	}

	@Bean
	JwtEncoder jwtEncoder(JwtSigningKey key) {
		return NimbusJwtEncoder.withKeyPair(key.publicKey(), key.privateKey())
			.jwkPostProcessor(builder -> builder.keyID(key.keyId()))
			.build();
	}

	@Bean
	JwtDecoder jwtDecoder(JwtSigningKey key, SecurityProperties properties) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(key.publicKey()).build();
		List<OAuth2TokenValidator<Jwt>> validators = List.of(new JwtIssuerValidator(properties.jwt().issuer()),
				new JwtAudienceValidator(properties.jwt().audience()),
				new JwtClaimValidator<Instant>(JwtClaimNames.EXP, Objects::nonNull),
				new JwtClaimValidator<Collection<?>>(ROLES_CLAIM, roles -> roles != null && !roles.isEmpty()));
		// Adds the default timestamp (exp/nbf with 60 s skew) validation to ours.
		decoder.setJwtValidator(JwtValidators.createDefaultWithValidators(validators));
		return decoder;
	}

}
