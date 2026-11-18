package com.bakedandbrowned.payment.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import com.bakedandbrowned.common.web.ApiException;
import com.bakedandbrowned.common.web.ApiExceptionHandler;
import com.bakedandbrowned.ordering.service.OrderPaymentOutcomes;
import com.bakedandbrowned.ordering.service.OrderPaymentOutcomes.PaymentOrder;
import com.bakedandbrowned.ordering.service.OrderQueryService;
import com.bakedandbrowned.payment.dto.PaymentDtos.ManualPaymentProvider;
import com.bakedandbrowned.payment.dto.PaymentDtos.ManualPaymentSubmission;
import com.bakedandbrowned.payment.proof.ProofFiles;
import com.bakedandbrowned.payment.proof.ProofStorage;

/**
 * Customer evidence for a MANUAL payment (ADR-0011). A submission is never proof of payment: the
 * payment stays PENDING until an ADMIN verifies it. Submitting within the hold pauses expiry while
 * the review runs (ADR-0008).
 * <p>
 * The optional proof file is checked by content and stored before the database transaction (no
 * file I/O while the order row is locked); if the transaction fails, the file is deleted again.
 */
@Service
public class ManualPaymentSubmissions {

	public static final String NOT_A_MANUAL_PAYMENT = "NOT_A_MANUAL_PAYMENT";

	public static final String INVALID_STATE = "INVALID_STATE";

	public static final String PAYMENT_HOLD_EXPIRED = "PAYMENT_HOLD_EXPIRED";

	public static final String SUBMISSION_ALREADY_OPEN = "SUBMISSION_ALREADY_OPEN";

	public static final String TRANSACTION_REFERENCE_IN_USE = "TRANSACTION_REFERENCE_IN_USE";

	public static final String UNSUPPORTED_PROOF_TYPE = "UNSUPPORTED_PROOF_TYPE";

	public static final String PROOF_TOO_LARGE = "PROOF_TOO_LARGE";

	/** After normalization: letters, digits and simple separators, 3 to 100 characters. */
	private static final Pattern REFERENCE = Pattern.compile("^[A-Z0-9][A-Z0-9 ._/-]{2,99}$");

	private final JdbcTemplate jdbc;

	private final TransactionTemplate transactions;

	private final OrderPaymentOutcomes orders;

	private final ProofStorage storage;

	private final Clock clock;

	public ManualPaymentSubmissions(JdbcTemplate jdbc, TransactionTemplate transactions, OrderPaymentOutcomes orders,
			ProofStorage storage, Clock clock) {
		this.jdbc = jdbc;
		this.transactions = transactions;
		this.orders = orders;
		this.storage = storage;
		this.clock = clock;
	}

	/**
	 * @param provider contract {@code ManualPaymentProvider}
	 * @param reference the provider's transaction reference; compared case-insensitively
	 * @throws ApiException 404 for a missing or someone else's order; 409 for the wrong payment
	 * method, state, an expired hold, an open submission or a reference already in use; 413 / 422
	 * for a proof file that is too large or not a JPEG, PNG or PDF; 422 for invalid fields
	 */
	public ManualPaymentSubmission submit(UUID orderId, UUID customerId, @Nullable String provider,
			@Nullable String reference, @Nullable MultipartFile proof) {
		ManualPaymentProvider parsedProvider = provider(provider);
		String normalizedReference = reference(reference);
		Upload upload = proof == null ? null : upload(proof);

		String key = upload == null ? null : storage.store(upload.content());
		try {
			return transactions.execute(status -> record(orderId, customerId, parsedProvider, normalizedReference,
					upload, key));
		}
		catch (RuntimeException ex) {
			if (key != null) {
				storage.delete(key);
			}
			throw ex;
		}
	}

	private ManualPaymentSubmission record(UUID orderId, UUID customerId, ManualPaymentProvider provider,
			String reference, @Nullable Upload upload, @Nullable String key) {
		Instant now = clock.instant();
		PaymentOrder order = orders.lock(orderId, now)
			.filter(o -> o.userId().equals(customerId))
			.orElseThrow(() -> ApiException.notFound(OrderQueryService.ORDER_NOT_FOUND, "Order not found."));
		if (!"MANUAL".equals(order.paymentMethod())) {
			throw ApiException.conflict(NOT_A_MANUAL_PAYMENT, "This order is not paid by manual transfer.");
		}
		if (!"PENDING_PAYMENT".equals(order.status())) {
			throw ApiException.conflict(INVALID_STATE, "This order is no longer waiting for payment.");
		}
		if (!order.holdOpen()) {
			throw ApiException.conflict(PAYMENT_HOLD_EXPIRED,
					"The time to pay for this order has run out. Please place a new order.");
		}
		UUID paymentId = jdbc.queryForObject("""
				SELECT id FROM payments WHERE order_id = ? AND superseded_at IS NULL AND status = 'PENDING'
				FOR UPDATE""", UUID.class, orderId);
		Integer open = jdbc.queryForObject(
				"SELECT count(*) FROM manual_payment_submissions WHERE payment_id = ? AND status = 'SUBMITTED'",
				Integer.class, paymentId);
		if (open != null && open > 0) {
			throw ApiException.conflict(SUBMISSION_ALREADY_OPEN,
					"We already have your payment details and are checking them.");
		}
		Integer used = jdbc.queryForObject("""
				SELECT count(*) FROM manual_payment_submissions
				WHERE provider = ? AND transaction_reference = ? AND status <> 'REJECTED'""", Integer.class,
				provider.name(), reference);
		if (used != null && used > 0) {
			throw referenceInUse();
		}
		try {
			List<Map<String, Object>> rows = jdbc.queryForList("""
					INSERT INTO manual_payment_submissions
					  (payment_id, submitted_by, provider, transaction_reference, proof_storage_key, proof_content_type,
					   proof_size_bytes, proof_sha256, created_at)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id, created_at""", paymentId, customerId,
					provider.name(), reference, key, upload == null ? null : upload.contentType(),
					upload == null ? null : upload.content().length, upload == null ? null : upload.sha256(),
					Timestamp.from(now));
			Map<String, Object> row = rows.get(0);
			return new ManualPaymentSubmission((UUID) row.get("id"), paymentId, provider.name(), reference,
					"SUBMITTED", key != null, now);
		}
		catch (DuplicateKeyException ex) {
			// Another order claimed the same reference between the check and the insert.
			throw referenceInUse();
		}
	}

	private static ApiException referenceInUse() {
		return ApiException.conflict(TRANSACTION_REFERENCE_IN_USE,
				"This transaction reference has already been submitted for a payment.");
	}

	private static ManualPaymentProvider provider(@Nullable String value) {
		if (value != null) {
			for (ManualPaymentProvider provider : ManualPaymentProvider.values()) {
				if (provider.name().equals(value.strip())) {
					return provider;
				}
			}
		}
		throw invalid("provider: must be one of BANK_TRANSFER, BKASH, NAGAD, ROCKET, OTHER");
	}

	/** Trimmed, inner whitespace collapsed, upper case, so one reference cannot be reused by retyping. */
	static String reference(@Nullable String value) {
		String normalized = value == null ? "" : value.strip().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
		if (!REFERENCE.matcher(normalized).matches()) {
			throw invalid("transactionReference: must be 3 to 100 letters, digits, spaces or . _ / -");
		}
		return normalized;
	}

	private static Upload upload(MultipartFile file) {
		if (file.getSize() > ProofFiles.MAX_BYTES) {
			throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, PROOF_TOO_LARGE, "The proof file must be at most 5 MB.");
		}
		byte[] content;
		try {
			content = file.getBytes();
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Cannot read the uploaded proof file", ex);
		}
		String type = ProofFiles.detectType(content)
			.orElseThrow(() -> ApiException.unprocessable(UNSUPPORTED_PROOF_TYPE,
					"The proof file must be a JPEG or PNG image or a PDF."));
		return new Upload(content, type, ProofFiles.sha256(content));
	}

	private static ApiException invalid(String detail) {
		return ApiException.unprocessable(ApiExceptionHandler.VALIDATION_FAILED, detail);
	}

	private record Upload(byte[] content, String contentType, String sha256) {
	}

}
