package com.bakedandbrowned.ordering.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.bakedandbrowned.common.value.Money;
import com.bakedandbrowned.ordering.dto.CheckoutDtos.CartLine;
import com.bakedandbrowned.ordering.dto.CheckoutDtos.CheckoutQuoteRequest;
import com.bakedandbrowned.ordering.dto.CheckoutDtos.DeliveryAddress;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Order placement and customer-safe order views (contract tag Orders). */
public final class OrderDtos {

	private OrderDtos() {
	}

	public enum PaymentMethod {
		COD, MANUAL, ONLINE
	}

	/** Contract {@code PlaceOrderRequest}: a quote request plus idempotency key and payment method. */
	public record PlaceOrderRequest(@NotNull UUID deliverySlotId, @NotNull @Valid DeliveryAddress delivery,
			@NotNull @Size(min = 1, max = CheckoutDtos.MAX_LINES) List<@NotNull @Valid CartLine> items,
			@NotNull UUID idempotencyKey, @NotNull PaymentMethod paymentMethod) {

		public CheckoutQuoteRequest cart() {
			return new CheckoutQuoteRequest(deliverySlotId, delivery, items);
		}

	}

	/** Contract {@code CancelOrderRequest}; the body itself is optional. */
	public record CancelOrderRequest(@Nullable @Size(max = 500) String reason) {
	}

	/** Contract {@code Order}. */
	public record OrderSummary(UUID orderId, String orderNumber, String status, String paymentStatus, Money subtotal,
			Money deliveryFee, Money discount, Money total, String currency, String paymentMethod, Instant createdAt) {
	}

	/** Contract {@code OrderItemCustomization}: immutable snapshot; text must be rendered escaped. */
	public record CustomizationSnapshot(UUID groupId, String groupName, @Nullable UUID optionId,
			@Nullable String optionName, @Nullable String textValue, Money priceDelta) {
	}

	/** Contract {@code OrderLine}. */
	public record OrderLineResponse(UUID productId, String productName, int quantity, Money unitBasePrice,
			Money unitOptionsPrice, Money unitPrice, Money lineTotal, List<CustomizationSnapshot> customizations) {
	}

	/** Contract {@code OrderDelivery}. */
	public record OrderDeliveryResponse(String name, String phone, String zoneCode, String zoneName, String address,
			@Nullable String notes, UUID slotId, Instant slotStartAt, Instant slotEndAt) {
	}

	/**
	 * Contract {@code OrderPaymentSummary}: no gateway identifiers or verification notes.
	 * {@code underReview} (v1.6): an open payment reconciliation case exists for the order.
	 */
	public record PaymentSummary(String method, String status, @Nullable String manualSubmissionStatus,
			@Nullable String refundStatus, boolean underReview) {
	}

	/** Contract {@code OrderStatusEvent}: no actor identities or admin notes. */
	public record StatusEvent(String status, Instant at) {
	}

	/** Contract {@code OrderDetail} (customer-safe superset of {@code Order}). */
	public record OrderDetail(UUID orderId, String orderNumber, String status, String paymentStatus, Money subtotal,
			Money deliveryFee, Money discount, Money total, String currency, String paymentMethod, Instant createdAt,
			Instant updatedAt, List<OrderLineResponse> items, OrderDeliveryResponse delivery, PaymentSummary payment,
			List<StatusEvent> statusHistory, @Nullable Instant expiresAt, Instant cancellationDeadline,
			boolean cancellable) {
	}

}
