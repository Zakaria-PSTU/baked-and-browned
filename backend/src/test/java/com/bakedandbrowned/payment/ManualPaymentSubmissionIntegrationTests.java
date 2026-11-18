package com.bakedandbrowned.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

import com.bakedandbrowned.ordering.service.HoldExpiryJob;
import com.bakedandbrowned.payment.proof.ProofFiles;
import com.bakedandbrowned.payment.proof.ProofStorage;
import com.bakedandbrowned.payment.service.ManualPaymentSubmissions;

/**
 * {@code POST /payments/{orderId}/manual-submission} (ADR-0011, business rules acceptance 13):
 * evidence never changes the payment, files are checked by content, references are single-use.
 */
class ManualPaymentSubmissionIntegrationTests extends ManualPaymentIntegrationTest {

	@Autowired
	private ProofStorage storage;

	@Autowired
	private HoldExpiryJob expiry;

	@Test
	void aSubmissionLeavesThePaymentPendingAndPausesExpiry() throws Exception {
		String customer = bearer("CUSTOMER");
		UUID orderId = placeOrder(customer, "MANUAL", slot, line(fudge, 1, six));

		submit(customer, orderId, "BKASH", "  8ab3 kq9z\t21 ", proof("statement.png", "image/png", PNG))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.paymentId").value(paymentOf(orderId).toString()))
			.andExpect(jsonPath("$.provider").value("BKASH"))
			.andExpect(jsonPath("$.transactionReference").value("8AB3 KQ9Z 21"))
			.andExpect(jsonPath("$.status").value("SUBMITTED"))
			.andExpect(jsonPath("$.hasProof").value(true))
			.andExpect(jsonPath("$.proofStorageKey").doesNotExist());

		assertThat(paymentStatus(orderId)).isEqualTo("PENDING");
		assertThat(orderStatus(orderId)).isEqualTo("PENDING_PAYMENT");
		Map<String, Object> row = jdbc.queryForMap("""
				SELECT m.proof_storage_key, m.proof_content_type, m.proof_size_bytes, m.proof_sha256, m.submitted_by
				FROM manual_payment_submissions m WHERE m.payment_id = ?""", paymentOf(orderId));
		assertThat(row).containsEntry("proof_content_type", "image/png")
			.containsEntry("proof_size_bytes", PNG.length)
			.containsEntry("proof_sha256", ProofFiles.sha256(PNG))
			.containsEntry("submitted_by", userIdOf(customer));
		assertThat(storage.read((String) row.get("proof_storage_key"))).isEqualTo(PNG);
		mockMvc.perform(get("/api/v1/orders/{id}", orderId).header(HttpHeaders.AUTHORIZATION, customer))
			.andExpect(jsonPath("$.payment.manualSubmissionStatus").value("SUBMITTED"))
			.andExpect(jsonPath("$.paymentStatus").value("PENDING"));

		// The hold runs out while the ADMIN is still checking: the order waits for the decision.
		overdue(orderId);
		jdbc.update("UPDATE manual_payment_submissions SET created_at = now() - interval '2 minutes' WHERE payment_id = ?",
				paymentOf(orderId));
		assertThat(expiry.expireDue()).isZero();
		assertThat(orderStatus(orderId)).isEqualTo("PENDING_PAYMENT");
	}

	@Test
	void theProofFileIsOptional() throws Exception {
		String customer = bearer("CUSTOMER");
		UUID orderId = placeOrder(customer, "MANUAL", slot, line(fudge, 1, six));

		submit(customer, orderId, "BANK_TRANSFER", "DBBL-778812", null).andExpect(status().isCreated())
			.andExpect(jsonPath("$.hasProof").value(false));
		assertThat(jdbc.queryForObject(
				"SELECT proof_storage_key IS NULL AND proof_sha256 IS NULL FROM manual_payment_submissions WHERE payment_id = ?",
				Boolean.class, paymentOf(orderId))).isTrue();
	}

	@Test
	void pdfAndJpegProofsAreAccepted() throws Exception {
		String customer = bearer("CUSTOMER");
		UUID pdfOrder = placeOrder(customer, "MANUAL", slot, line(fudge, 1, six));
		UUID jpegOrder = placeOrder(customer, "MANUAL", slot, line(fudge, 1, six));

		// The declared type is ignored: the content decides.
		submit(customer, pdfOrder, "NAGAD", "NG-1001", proof("receipt", "application/octet-stream",
				"%PDF-1.7\n%...".getBytes(StandardCharsets.US_ASCII)))
			.andExpect(status().isCreated());
		submit(customer, jpegOrder, "ROCKET", "RK-1002",
				proof("photo.png", "image/png", new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0 }))
			.andExpect(status().isCreated());

		assertThat(jdbc.queryForList("""
				SELECT m.proof_content_type FROM manual_payment_submissions m JOIN payments p ON p.id = m.payment_id
				ORDER BY m.provider""", String.class)).containsExactly("application/pdf", "image/jpeg");
	}

	@Test
	void aForgedFileTypeIsRejectedAndNothingIsKept() throws Exception {
		String customer = bearer("CUSTOMER");
		UUID orderId = placeOrder(customer, "MANUAL", slot, line(fudge, 1, six));
		long filesBefore = storedFiles();

		submit(customer, orderId, "BKASH", "TRX-FORGED",
				proof("proof.png", "image/png", "<html><script>alert(1)</script>".getBytes(StandardCharsets.UTF_8)))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value(ManualPaymentSubmissions.UNSUPPORTED_PROOF_TYPE));
		submit(customer, orderId, "BKASH", "TRX-EMPTY", proof("proof.png", "image/png", new byte[0]))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value(ManualPaymentSubmissions.UNSUPPORTED_PROOF_TYPE));

		assertThat(count("manual_payment_submissions")).isZero();
		assertThat(storedFiles()).isEqualTo(filesBefore);
	}

	@Test
	void aProofOver5MegabytesIsTooLarge() throws Exception {
		String customer = bearer("CUSTOMER");
		UUID orderId = placeOrder(customer, "MANUAL", slot, line(fudge, 1, six));
		byte[] big = Arrays.copyOf(PNG, (int) ProofFiles.MAX_BYTES + 1);

		submit(customer, orderId, "BKASH", "TRX-BIG", proof("big.png", "image/png", big))
			.andExpect(status().is(413))
			.andExpect(jsonPath("$.code").value(ManualPaymentSubmissions.PROOF_TOO_LARGE));
		assertThat(count("manual_payment_submissions")).isZero();

		// Exactly 5 MB is fine.
		submit(customer, orderId, "BKASH", "TRX-MAX", proof("max.png", "image/png", Arrays.copyOf(PNG,
				(int) ProofFiles.MAX_BYTES)))
			.andExpect(status().isCreated());
	}

	@Test
	void invalidFieldsAreValidationErrors() throws Exception {
		// One customer per case: the submission rate limit counts invalid attempts too.
		for (String[] fields : new String[][] { { "PAYPAL", "TRX-1" }, { null, "TRX-1" }, { "BKASH", null },
				{ "BKASH", "AB" }, { "BKASH", "TRX<script>" }, { "BKASH", "X".repeat(101) } }) {
			String customer = bearer("CUSTOMER");
			UUID orderId = placeOrder(customer, "MANUAL", slot, line(cookies, 1));
			submit(customer, orderId, fields[0], fields[1], null).andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		}
		assertThat(count("manual_payment_submissions")).isZero();
	}

	@Test
	void submissionsAreRateLimitedPerCustomer() throws Exception {
		String customer = bearer("CUSTOMER");
		UUID orderId = placeOrder(customer, "MANUAL", slot, line(cookies, 1));
		for (int i = 0; i < 5; i++) {
			submit(customer, orderId, "BKASH", "AB", null).andExpect(status().isUnprocessableContent());
		}

		submit(customer, orderId, "BKASH", "TRX-VALID", null).andExpect(status().isTooManyRequests())
			.andExpect(header().exists(HttpHeaders.RETRY_AFTER));
		// Another customer is unaffected.
		String other = bearer("CUSTOMER");
		submitted(other, placeOrder(other, "MANUAL", slot, line(cookies, 1)), "TRX-VALID");
	}

	@Test
	void onlyOneOpenSubmissionPerPaymentAndEachReferenceOnce() throws Exception {
		String customer = bearer("CUSTOMER");
		UUID first = placeOrder(customer, "MANUAL", slot, line(fudge, 1, six));
		UUID second = placeOrder(customer, "MANUAL", slot, line(fudge, 1, six));
		submitted(customer, first, "TRX-0001");

		submit(customer, first, "BKASH", "TRX-0002", null).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(ManualPaymentSubmissions.SUBMISSION_ALREADY_OPEN));
		// The same reference for another order, also when retyped in lower case.
		String other = bearer("CUSTOMER");
		submit(other, placeOrder(other, "MANUAL", slot, line(cookies, 1)), "BKASH", "TRX-0001", null)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(ManualPaymentSubmissions.TRANSACTION_REFERENCE_IN_USE));
		submit(customer, second, "BKASH", "trx-0001", null).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(ManualPaymentSubmissions.TRANSACTION_REFERENCE_IN_USE));
		// Another provider may use the same reference text.
		submit(customer, second, "NAGAD", "TRX-0001", null).andExpect(status().isCreated());
	}

	@Test
	void onlyTheOwnerOfAnOpenManualOrderMaySubmit() throws Exception {
		String owner = bearer("CUSTOMER");
		UUID manual = placeOrder(owner, "MANUAL", slot, line(fudge, 1, six));
		UUID cod = placeOrder(owner, "COD", slot, line(cookies, 1));
		UUID online = placeOrder(owner, "ONLINE", slot, line(cookies, 1));

		submit(bearer("CUSTOMER"), manual, "BKASH", "TRX-A1", null).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
		submit(owner, UUID.randomUUID(), "BKASH", "TRX-A1", null).andExpect(status().isNotFound());
		submit(owner, cod, "BKASH", "TRX-A1", null).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(ManualPaymentSubmissions.NOT_A_MANUAL_PAYMENT));
		submit(owner, online, "BKASH", "TRX-A1", null).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(ManualPaymentSubmissions.NOT_A_MANUAL_PAYMENT));
		mockMvc.perform(multipart("/api/v1/payments/{id}/manual-submission", manual).param("provider", "BKASH")
			.param("transactionReference", "TRX-A1")).andExpect(status().isUnauthorized());
		assertThat(count("manual_payment_submissions")).isZero();
	}

	@Test
	void noSubmissionAfterTheHoldOrOnAClosedOrder() throws Exception {
		String customer = bearer("CUSTOMER");
		UUID late = placeOrder(customer, "MANUAL", slot, line(fudge, 1, six));
		UUID cancelled = placeOrder(customer, "MANUAL", slot, line(fudge, 1, six));
		overdue(late);
		cancel(customer, cancelled, null).andExpect(status().isOk());

		// The expiry job has not run yet, but the hold is over.
		submit(customer, late, "BKASH", "TRX-LATE", null).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(ManualPaymentSubmissions.PAYMENT_HOLD_EXPIRED));
		submit(customer, cancelled, "BKASH", "TRX-CANC", null).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(ManualPaymentSubmissions.INVALID_STATE));
		assertThat(count("manual_payment_submissions")).isZero();
	}

}
