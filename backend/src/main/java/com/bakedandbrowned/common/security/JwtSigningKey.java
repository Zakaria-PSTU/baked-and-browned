package com.bakedandbrowned.common.security;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

/**
 * The RSA key pair that signs (private) and verifies (public) access tokens, with its {@code kid}.
 * {@link #toString()} never includes key material.
 */
public record JwtSigningKey(String keyId, RSAPublicKey publicKey, RSAPrivateKey privateKey) {

	static final int MIN_KEY_BITS = 2048;

	/** Parses a PKCS#8 PEM ({@code BEGIN PRIVATE KEY}) RSA key and derives its public key. */
	public static JwtSigningKey fromPem(String keyId, String pem) {
		String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
			.replace("-----END PRIVATE KEY-----", "")
			.replaceAll("\\s", "");
		if (base64.isEmpty() || pem.contains("RSA PRIVATE KEY")) {
			throw new IllegalStateException(
					"app.security.jwt.private-key must be a PKCS#8 PEM RSA key (BEGIN PRIVATE KEY); "
							+ "convert a PKCS#1 key with: openssl pkcs8 -topk8 -nocrypt");
		}
		try {
			KeyFactory rsa = KeyFactory.getInstance("RSA");
			if (!(rsa.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)))
					instanceof RSAPrivateCrtKey privateKey)) {
				throw new IllegalStateException("app.security.jwt.private-key must be an RSA private key");
			}
			RSAPublicKey publicKey = (RSAPublicKey) rsa
				.generatePublic(new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
			return checked(new JwtSigningKey(keyId, publicKey, privateKey));
		}
		catch (GeneralSecurityException | IllegalArgumentException ex) {
			throw new IllegalStateException("app.security.jwt.private-key is not a valid PKCS#8 RSA key", ex);
		}
	}

	/** A throwaway key for tests and local development. */
	public static JwtSigningKey generateEphemeral(String keyId) {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(MIN_KEY_BITS);
			KeyPair pair = generator.generateKeyPair();
			return new JwtSigningKey(keyId, (RSAPublicKey) pair.getPublic(), (RSAPrivateKey) pair.getPrivate());
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("RSA key generation is unavailable", ex);
		}
	}

	private static JwtSigningKey checked(JwtSigningKey key) {
		if (key.publicKey.getModulus().bitLength() < MIN_KEY_BITS) {
			throw new IllegalStateException("app.security.jwt.private-key must be at least " + MIN_KEY_BITS + " bits");
		}
		return key;
	}

	@Override
	public String toString() {
		return "JwtSigningKey[keyId=" + keyId + ", bits=" + publicKey.getModulus().bitLength() + "]";
	}

}
