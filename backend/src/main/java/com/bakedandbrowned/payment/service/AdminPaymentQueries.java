package com.bakedandbrowned.payment.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bakedandbrowned.common.value.Money;
import com.bakedandbrowned.common.web.ApiException;
import com.bakedandbrowned.common.web.PageResponse;
import com.bakedandbrowned.payment.dto.PaymentDtos.AdminPayment;
import com.bakedandbrowned.payment.dto.PaymentDtos.ManualPaymentSubmission;
import com.bakedandbrowned.payment.dto.PaymentDtos.Refund;
import com.bakedandbrowned.payment.proof.ProofFiles;
import com.bakedandbrowned.payment.proof.ProofStorage;

/** ADMIN payment views: the verification queue, one payment, and its latest proof file. */
@Service
@Transactional(readOnly = true)
public class AdminPaymentQueries {

	public static final String PAYMENT_NOT_FOUND = "PAYMENT_NOT_FOUND";

	public static final String PROOF_NOT_FOUND = "PROOF_NOT_FOUND";

	private static final String COLUMNS = """
			p.id, p.order_id, o.order_number, p.method::text AS method, p.provider, p.provider_transaction_id,
			p.amount, p.currency, p.status::text AS status, p.failure_reason, p.verified_by, p.verified_at,
			p.verification_note, p.created_at,
			(SELECT coalesce(sum(r.amount), 0) FROM refunds r WHERE r.payment_id = p.id AND r.status = 'COMPLETED')
			  AS refunded_amount,
			m.id AS m_id, m.provider AS m_provider, m.transaction_reference AS m_reference,
			m.status::text AS m_status, m.proof_storage_key IS NOT NULL AS m_has_proof, m.created_at AS m_created_at
			FROM payments p
			JOIN orders o ON o.id = p.order_id
			LEFT JOIN LATERAL (
			  SELECT * FROM manual_payment_submissions s WHERE s.payment_id = p.id
			  ORDER BY s.created_at DESC, s.id DESC LIMIT 1) m ON TRUE""";

	private final JdbcTemplate jdbc;

	private final ProofStorage storage;

	public AdminPaymentQueries(JdbcTemplate jdbc, ProofStorage storage) {
		this.jdbc = jdbc;
		this.storage = storage;
	}

	/**
	 * Payments, newest first. {@code awaitingVerification} keeps only MANUAL payments with an open
	 * (SUBMITTED) submission, oldest first, which is the order an ADMIN should work through them.
	 */
	public PageResponse<AdminPayment> list(@Nullable String status, @Nullable String method,
			boolean awaitingVerification, int page, int size) {
		StringBuilder where = new StringBuilder(" WHERE TRUE");
		List<Object> params = new ArrayList<>();
		if (status != null) {
			where.append(" AND p.status = ?::payment_status");
			params.add(status);
		}
		if (method != null) {
			where.append(" AND p.method = ?::payment_method");
			params.add(method);
		}
		if (awaitingVerification) {
			where.append(" AND p.method = 'MANUAL' AND m.status = 'SUBMITTED'");
		}
		String order = awaitingVerification ? " ORDER BY m.created_at, p.id" : " ORDER BY p.created_at DESC, p.id DESC";
		Long total = jdbc.queryForObject("SELECT count(*) FROM (SELECT " + COLUMNS + where + ") counted", Long.class,
				params.toArray());
		List<Object> pageParams = new ArrayList<>(params);
		pageParams.add(size);
		pageParams.add((long) page * size);
		List<AdminPayment> content = jdbc.query("SELECT " + COLUMNS + where + order + " LIMIT ? OFFSET ?",
				(rs, row) -> payment(rs), pageParams.toArray());
		return new PageResponse<>(content, page, size, total == null ? 0 : total);
	}

	public AdminPayment get(UUID paymentId) {
		return jdbc.query("SELECT " + COLUMNS + " WHERE p.id = ?", (rs, row) -> payment(rs), paymentId)
			.stream()
			.findFirst()
			.orElseThrow(() -> ApiException.notFound(PAYMENT_NOT_FOUND, "Payment not found."));
	}

	/** Every payment of the order, including superseded attempts, oldest first. */
	public List<AdminPayment> forOrder(UUID orderId) {
		return jdbc.query("SELECT " + COLUMNS + " WHERE p.order_id = ? ORDER BY p.created_at, p.id",
				(rs, row) -> payment(rs), orderId);
	}

	/** Every refund request of the order, oldest first. */
	public List<Refund> refunds(UUID orderId) {
		return jdbc.query("SELECT " + REFUND_COLUMNS + " FROM refunds WHERE order_id = ? ORDER BY created_at, id",
				(rs, row) -> refund(rs), orderId);
	}

	static final String REFUND_COLUMNS = """
			id, payment_id, order_id, amount, currency, status::text AS status, request_reason,
			requested_by_type::text AS requested_by_type, decided_by, decided_at, decision_note,
			external_reference, completed_at, created_at""";

	static Refund refund(ResultSet rs) throws SQLException {
		return new Refund(rs.getObject("id", UUID.class), rs.getObject("payment_id", UUID.class),
				rs.getObject("order_id", UUID.class), Money.of(rs.getBigDecimal("amount")),
				rs.getString("currency").strip(), rs.getString("status"), rs.getString("request_reason"),
				rs.getString("requested_by_type"), rs.getObject("decided_by", UUID.class), instant(rs, "decided_at"),
				rs.getString("decision_note"), rs.getString("external_reference"), instant(rs, "completed_at"),
				rs.getTimestamp("created_at").toInstant());
	}

	private static java.time.@Nullable Instant instant(ResultSet rs, String column) throws SQLException {
		java.sql.Timestamp value = rs.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

	/** The proof file of the payment's latest submission. */
	public Proof proof(UUID paymentId) {
		AdminPayment payment = get(paymentId);
		ManualPaymentSubmission latest = payment.latestSubmission();
		if (latest == null || !latest.hasProof()) {
			throw ApiException.notFound(PROOF_NOT_FOUND, "This payment has no proof file.");
		}
		var file = jdbc.queryForMap(
				"SELECT proof_storage_key, proof_content_type FROM manual_payment_submissions WHERE id = ?", latest.id());
		String type = (String) file.get("proof_content_type");
		return new Proof(storage.read((String) file.get("proof_storage_key")), type,
				"payment-proof-" + payment.orderNumber() + "." + ProofFiles.extension(type));
	}

	public record Proof(byte[] content, String contentType, String fileName) {
	}

	static ManualPaymentSubmission submission(ResultSet rs, String prefix, UUID paymentId) throws SQLException {
		return new ManualPaymentSubmission(rs.getObject(prefix + "id", UUID.class), paymentId,
				rs.getString(prefix + "provider"), rs.getString(prefix + "reference"), rs.getString(prefix + "status"),
				rs.getBoolean(prefix + "has_proof"), rs.getTimestamp(prefix + "created_at").toInstant());
	}

	private static AdminPayment payment(ResultSet rs) throws SQLException {
		UUID id = rs.getObject("id", UUID.class);
		java.sql.Timestamp verifiedAt = rs.getTimestamp("verified_at");
		return new AdminPayment(id, rs.getObject("order_id", UUID.class), rs.getString("order_number"),
				rs.getString("method"), rs.getString("provider"), rs.getString("provider_transaction_id"),
				Money.of(rs.getBigDecimal("amount")), rs.getString("currency").strip(), rs.getString("status"),
				rs.getString("failure_reason"), rs.getObject("verified_by", UUID.class),
				verifiedAt == null ? null : verifiedAt.toInstant(), rs.getString("verification_note"),
				rs.getObject("m_id") == null ? null : submission(rs, "m_", id),
				Money.of(rs.getBigDecimal("refunded_amount")),
				rs.getTimestamp("created_at").toInstant());
	}

}
