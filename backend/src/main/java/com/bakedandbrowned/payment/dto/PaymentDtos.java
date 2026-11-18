package com.bakedandbrowned.payment.dto;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.bakedandbrowned.common.value.Money;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Manual payment submission and ADMIN payment views (contract tags Payments and Admin). */
public final class PaymentDtos {

	private PaymentDtos() {
	}

	/** Contract {@code ReconciliationCase}; {@code reason} is the case's machine-readable cause, if recorded. */
	public record ReconciliationCase(UUID id, UUID paymentId, UUID orderId, String orderNumber, String caseType,
			String status, @Nullable String reason, @Nullable String resolution, @Nullable String resolutionNote,
			@Nullable Instant resolvedAt, Instant createdAt) {
	}

	/** Contract {@code ReconciliationResolutionRequest}. */
	public record ReconciliationResolutionRequest(@NotNull Action action,
			@NotBlank @Size(min = 3, max = 500) String note) {

		public enum Action {
			REINSTATE, REQUEST_REFUND, NO_ACTION
		}

	}

	/** Contract {@code initiatePayment} 200 body: where to send the customer's browser. */
	public record PaymentInitiation(UUID paymentId, java.net.URI checkoutUrl) {
	}

	/** Contract {@code ManualPaymentProvider}. */
	public enum ManualPaymentProvider {
		BANK_TRANSFER, BKASH, NAGAD, ROCKET, OTHER
	}

	/** Contract {@code ManualPaymentAccount}: where the customer sends a MANUAL payment (ADR-0022). */
	public record ManualPaymentAccount(ManualPaymentProvider provider, String label, String accountName,
			String accountNumber, @Nullable String note) {
	}

	/** Contract {@code ManualPaymentSubmission}: no storage key, file hash or submitter. */
	public record ManualPaymentSubmission(UUID id, UUID paymentId, String provider, String transactionReference,
			String status, boolean hasProof, Instant createdAt) {
	}

	/** Contract {@code PaymentVerificationRequest}. */
	public record PaymentVerificationRequest(@NotNull Decision decision, @NotBlank @Size(min = 3, max = 500) String note) {

		public enum Decision {
			APPROVE, REJECT
		}

	}

	/**
	 * Body of {@code adminRequestRefund}. {@code amount} defaults to everything not yet refunded;
	 * a smaller amount is a partial refund.
	 */
	public record RefundRequest(@NotBlank @Size(min = 3, max = 500) String reason, @Nullable Money amount) {
	}

	/** Contract {@code RefundDecisionRequest}. */
	public record RefundDecisionRequest(@NotNull PaymentVerificationRequest.Decision decision,
			@NotBlank @Size(min = 3, max = 500) String note) {
	}

	/** Contract {@code RefundCompletionRequest}: the bank or wallet reference of the money sent back. */
	public record RefundCompletionRequest(@NotBlank @Size(min = 3, max = 150) String externalReference) {
	}

	/** Contract {@code Refund}. */
	public record Refund(UUID id, UUID paymentId, UUID orderId, Money amount, String currency, String status,
			String requestReason, String requestedByType, @Nullable UUID decidedBy, @Nullable Instant decidedAt,
			@Nullable String decisionNote, @Nullable String externalReference, @Nullable Instant completedAt,
			Instant createdAt) {
	}

	/** Contract {@code AdminPayment}; {@code refundedAmount} is the total of completed refunds (v1.2). */
	public record AdminPayment(UUID id, UUID orderId, String orderNumber, String method, @Nullable String provider,
			@Nullable String providerTransactionId, Money amount, String currency, String status,
			@Nullable String failureReason, @Nullable UUID verifiedBy, @Nullable Instant verifiedAt,
			@Nullable String verificationNote, @Nullable ManualPaymentSubmission latestSubmission, Money refundedAmount,
			Instant createdAt) {
	}

}
