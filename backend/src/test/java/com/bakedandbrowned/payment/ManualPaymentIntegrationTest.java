package com.bakedandbrowned.payment;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import com.bakedandbrowned.ordering.CheckoutIntegrationTest;

/** Manual payment fixture on top of the checkout fixture: submissions, proof files and decisions. */
abstract class ManualPaymentIntegrationTest extends CheckoutIntegrationTest {

	/** A minimal PNG signature plus some bytes: enough for content detection. */
	static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R' };

	static final Path PROOF_DIRECTORY = Path.of("target/test-payment-proofs");

	protected ResultActions submit(String bearer, UUID orderId, @Nullable String provider, @Nullable String reference,
			@Nullable MockMultipartFile proof) throws Exception {
		MockMultipartHttpServletRequestBuilder request = multipart("/api/v1/payments/{orderId}/manual-submission",
				orderId);
		if (proof != null) {
			request.file(proof);
		}
		if (provider != null) {
			request.param("provider", provider);
		}
		if (reference != null) {
			request.param("transactionReference", reference);
		}
		return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer));
	}

	/** Submits a BKASH reference without proof and expects 201. */
	protected void submitted(String bearer, UUID orderId, String reference) throws Exception {
		submit(bearer, orderId, "BKASH", reference, null).andExpect(status().isCreated());
	}

	protected static MockMultipartFile proof(String name, String declaredType, byte[] content) {
		return new MockMultipartFile("proof", name, declaredType, content);
	}

	protected ResultActions decide(String adminBearer, UUID paymentId, String decision, String note) throws Exception {
		return mockMvc.perform(post("/api/v1/admin/payments/{id}/verification", paymentId)
			.header(HttpHeaders.AUTHORIZATION, adminBearer)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"decision\":\"" + decision + "\",\"note\":\"" + note + "\"}"));
	}

	protected UUID paymentOf(UUID orderId) {
		return jdbc.queryForObject("SELECT id FROM payments WHERE order_id = ? AND superseded_at IS NULL", UUID.class,
				orderId);
	}

	protected String submissionStatus(UUID orderId) {
		return jdbc.queryForObject("""
				SELECT m.status::text FROM manual_payment_submissions m JOIN payments p ON p.id = m.payment_id
				WHERE p.order_id = ? ORDER BY m.created_at DESC LIMIT 1""", String.class, orderId);
	}

	/** Moves the order's payment hold into the past. */
	protected void overdue(UUID orderId) {
		jdbc.update("UPDATE orders SET expires_at = now() - interval '1 minute' WHERE id = ?", orderId);
	}

	protected static long storedFiles() {
		if (!Files.isDirectory(PROOF_DIRECTORY)) {
			return 0;
		}
		try (Stream<Path> files = Files.list(PROOF_DIRECTORY)) {
			return files.count();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
