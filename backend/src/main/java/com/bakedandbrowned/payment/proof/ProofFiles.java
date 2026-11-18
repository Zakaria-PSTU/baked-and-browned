package com.bakedandbrowned.payment.proof;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Proof-file rules (ADR-0011): JPEG, PNG or PDF of at most 5 MB, recognised by their content
 * (magic bytes), never by the file name or the declared content type.
 */
public final class ProofFiles {

	public static final long MAX_BYTES = 5L * 1024 * 1024;

	private static final byte[] JPEG = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF };

	private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };

	private static final byte[] PDF = { '%', 'P', 'D', 'F', '-' };

	private ProofFiles() {
	}

	/** The content type the bytes really are, if they are an accepted format. */
	public static Optional<String> detectType(byte[] content) {
		if (startsWith(content, JPEG)) {
			return Optional.of("image/jpeg");
		}
		if (startsWith(content, PNG)) {
			return Optional.of("image/png");
		}
		if (startsWith(content, PDF)) {
			return Optional.of("application/pdf");
		}
		return Optional.empty();
	}

	/** File extension for a detected type, for download names. */
	public static String extension(String contentType) {
		return switch (contentType) {
			case "image/jpeg" -> "jpg";
			case "image/png" -> "png";
			case "application/pdf" -> "pdf";
			default -> throw new IllegalArgumentException("Unsupported proof type " + contentType);
		};
	}

	public static String sha256(byte[] content) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is required by every Java platform", ex);
		}
	}

	private static boolean startsWith(byte[] content, byte[] prefix) {
		if (content.length < prefix.length) {
			return false;
		}
		for (int i = 0; i < prefix.length; i++) {
			if (content[i] != prefix[i]) {
				return false;
			}
		}
		return true;
	}

}
