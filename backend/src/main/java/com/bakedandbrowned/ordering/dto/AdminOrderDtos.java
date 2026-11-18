package com.bakedandbrowned.ordering.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.bakedandbrowned.ordering.dto.OrderDtos.OrderDetail;
import com.bakedandbrowned.payment.dto.PaymentDtos.AdminPayment;
import com.bakedandbrowned.payment.dto.PaymentDtos.Refund;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** ADMIN order operations (contract tag Admin). */
public final class AdminOrderDtos {

	private AdminOrderDtos() {
	}

	/** Contract {@code OrderStatus}. */
	public enum OrderStatus {
		PENDING_PAYMENT, CONFIRMED, PREPARING, READY, OUT_FOR_DELIVERY, DELIVERED, CANCELLED, EXPIRED
	}

	/** Body of {@code adminChangeOrderStatus}; {@code reason} is required for CANCELLED. */
	public record StatusChangeRequest(@NotNull OrderStatus status, @Nullable @Size(min = 3, max = 500) String reason) {
	}

	/** Contract {@code AdminOrderStatusEvent}: with actors and reasons, unlike the customer view. */
	public record AdminOrderStatusEvent(@Nullable String fromStatus, String toStatus, String actorType,
			@Nullable UUID actorUserId, @Nullable String reason, Instant at) {
	}

	/**
	 * Contract {@code AdminOrderDetail}: the customer {@code OrderDetail} fields (flattened) plus
	 * the customer's identity, the full history, every payment and every refund.
	 */
	public record AdminOrderDetail(@JsonUnwrapped OrderDetail order, UUID customerUserId, String customerName,
			String customerPhone, List<AdminOrderStatusEvent> statusEvents, List<AdminPayment> payments,
			List<Refund> refunds) {
	}

}
