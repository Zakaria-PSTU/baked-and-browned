package com.bakedandbrowned.payment.proof;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class ProofFilesTest {

	@Test
	void recognisesAcceptedFormatsByTheirMagicBytes() {
		assertThat(ProofFiles.detectType(new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xDB }))
			.contains("image/jpeg");
		assertThat(ProofFiles.detectType(new byte[] { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0 }))
			.contains("image/png");
		assertThat(ProofFiles.detectType("%PDF-1.4".getBytes(StandardCharsets.US_ASCII))).contains("application/pdf");
	}

	@Test
	void rejectsEverythingElseIncludingTruncatedSignatures() {
		assertThat(ProofFiles.detectType(new byte[0])).isEmpty();
		assertThat(ProofFiles.detectType(new byte[] { (byte) 0xFF, (byte) 0xD8 })).isEmpty();
		assertThat(ProofFiles.detectType(new byte[] { (byte) 0x89, 'P', 'N', 'G' })).isEmpty();
		assertThat(ProofFiles.detectType("%PDF".getBytes(StandardCharsets.US_ASCII))).isEmpty();
		assertThat(ProofFiles.detectType("GIF89a".getBytes(StandardCharsets.US_ASCII))).isEmpty();
		assertThat(ProofFiles.detectType("<svg onload=alert(1)>".getBytes(StandardCharsets.UTF_8))).isEmpty();
	}

	@Test
	void extensionsAndHashes() {
		assertThat(ProofFiles.extension("image/jpeg")).isEqualTo("jpg");
		assertThat(ProofFiles.extension("application/pdf")).isEqualTo("pdf");
		assertThatIllegalArgumentException().isThrownBy(() -> ProofFiles.extension("image/gif"));
		assertThat(ProofFiles.sha256("abc".getBytes(StandardCharsets.US_ASCII)))
			.isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
	}

}
