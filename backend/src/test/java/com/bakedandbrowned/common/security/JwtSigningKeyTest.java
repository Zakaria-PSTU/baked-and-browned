package com.bakedandbrowned.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;

class JwtSigningKeyTest {

	@Test
	void parsesPkcs8PemAndDerivesTheMatchingPublicKey() {
		JwtSigningKey generated = JwtSigningKey.generateEphemeral("k1");
		JwtSigningKey parsed = JwtSigningKey.fromPem("k1", pem(generated.privateKey().getEncoded()));

		assertThat(parsed.publicKey()).isEqualTo(generated.publicKey());
		assertThat(parsed.keyId()).isEqualTo("k1");
		assertThat(parsed.toString()).doesNotContain(generated.publicKey().getModulus().toString()).contains("bits=2048");
	}

	@Test
	void rejectsPkcs1GarbageAndShortKeys() throws Exception {
		assertThatIllegalStateException()
			.isThrownBy(() -> JwtSigningKey.fromPem("k", "-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----"))
			.withMessageContaining("PKCS#8");
		assertThatIllegalStateException().isThrownBy(() -> JwtSigningKey.fromPem("k", "not a key"));

		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(1024);
		String weak = pem(generator.generateKeyPair().getPrivate().getEncoded());
		assertThatIllegalStateException().isThrownBy(() -> JwtSigningKey.fromPem("k", weak))
			.withMessageContaining("2048");
	}

	@Test
	void startupFailsWithoutAKeyUnlessEphemeralIsExplicitlyAllowed() {
		JwtConfig config = new JwtConfig();
		assertThatIllegalStateException().isThrownBy(() -> config.jwtSigningKey(properties(null, false)))
			.withMessageContaining("APP_SECURITY_JWT_PRIVATE_KEY");
		assertThat(config.jwtSigningKey(properties(null, true)).keyId()).isEqualTo("kid");
	}

	@Test
	void propertiesNeverPrintTheKey() {
		String pem = pem(JwtSigningKey.generateEphemeral("k").privateKey().getEncoded());
		assertThat(properties(pem, false).toString()).doesNotContain(pem.substring(40, 80)).contains("<redacted>");
	}

	private static SecurityProperties properties(String privateKey, boolean ephemeral) {
		return new SecurityProperties(
				new SecurityProperties.Jwt("iss", "aud", Duration.ofMinutes(15), "kid", privateKey, ephemeral),
				Duration.ofDays(14), true, List.of());
	}

	private static String pem(byte[] pkcs8) {
		return "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(pkcs8)
				+ "\n-----END PRIVATE KEY-----\n";
	}

}
