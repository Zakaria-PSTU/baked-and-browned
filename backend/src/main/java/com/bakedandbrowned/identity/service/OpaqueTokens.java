package com.bakedandbrowned.identity.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Random opaque tokens (256 bits, base64url) and their SHA-256 hex hash for storage. */
public final class OpaqueTokens {

	private static final SecureRandom RANDOM = new SecureRandom();

	private OpaqueTokens() {
	}

	public static String generate() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/** 64 lowercase hex characters, matching {@code refresh_tokens.token_hash CHAR(64)}. */
	public static String sha256Hex(String token) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is unavailable", ex);
		}
	}

}
