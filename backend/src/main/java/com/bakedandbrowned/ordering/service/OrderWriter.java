package com.bakedandbrowned.ordering.service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.bakedandbrowned.common.outbox.OutboxPublisher;
import com.bakedandbrowned.common.time.BusinessTime;
import com.bakedandbrowned.common.web.ApiException;
import com.bakedandbrowned.customization.service.ValidatedCustomization.Choice;
import com.bakedandbrowned.inventory.service.StockReservations;
import com.bakedandbrowned.ordering.dto.OrderDtos.PaymentMethod;
import com.bakedandbrowned.ordering.dto.OrderDtos.PlaceOrderRequest;
import com.bakedandbrowned.ordering.service.PricedCart.PricedLine;
import com.bakedandbrowned.scheduling.service.SlotCapacity;

/**
 * Writes one order and its reservations in a single short transaction (architecture, Core checkout
 * consistency, step 4). Lock order is deterministic: the new order row, then the slot, then
 * ingredients by id. Any failure rolls back everything, including reservations already taken.
 * <p>
 * Orders are written with SQL, not JPA entities, so no snapshot can ever be changed by accidental
 * dirty checking after placement.
 */
@Component
class OrderWriter {

	/** ADR-0008: ONLINE and MANUAL orders hold their reservations for 15 minutes. */
	static final Duration PAYMENT_HOLD = Duration.ofMinutes(15);

	private static final DateTimeFormatter ORDER_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

	private final JdbcTemplate jdbc;

	private final SlotCapacity slotCapacity;

	private final StockReservations stock;

	private final OutboxPublisher outbox;

	OrderWriter(JdbcTemplate jdbc, SlotCapacity slotCapacity, StockReservations stock, OutboxPublisher outbox) {
		this.jdbc = jdbc;
		this.slotCapacity = slotCapacity;
		this.stock = stock;
		this.outbox = outbox;
	}

	/**
	 * @throws ApiException 409 {@link QuoteService#SLOT_FULL} or
	 * {@link StockReservations#INSUFFICIENT_STOCK} when the last capacity or stock went to someone
	 * else first
	 * @throws org.springframework.dao.DuplicateKeyException when a concurrent request with the same
	 * idempotency key committed first
	 */
	@Transactional
	public UUID write(UUID userId, PlaceOrderRequest request, String requestHash, PricedCart cart, Instant now) {
		boolean cod = request.paymentMethod() == PaymentMethod.COD;
		String status = cod ? "CONFIRMED" : "PENDING_PAYMENT";
		Timestamp expiresAt = cod ? null : Timestamp.from(now.plus(PAYMENT_HOLD));

		UUID orderId = jdbc.queryForObject("""
				INSERT INTO orders (order_number, user_id, delivery_slot_id, status, customer_name, customer_phone,
				                    delivery_address, delivery_area, delivery_notes, delivery_zone_id, subtotal,
				                    delivery_fee, discount_amount, total_amount, payment_method, idempotency_key,
				                    expires_at, cancellation_deadline, request_hash)
				VALUES (?, ?, ?, ?::order_status, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::payment_method, ?, ?, ?, ?)
				RETURNING id""", UUID.class, orderNumber(now), userId, cart.slot().id(), status,
				request.delivery().name().strip(), request.delivery().phone().value(),
				request.delivery().address().strip(), cart.zone().name(), blankToNull(request.delivery().notes()),
				cart.zone().id(), cart.subtotal().amount(), cart.deliveryFee().amount(), cart.discount().amount(),
				cart.total().amount(), request.paymentMethod().name(), request.idempotencyKey(), expiresAt,
				Timestamp.from(cart.cancellationDeadline()), requestHash);

		if (!slotCapacity.tryReserve(cart.slot().id(), cart.capacityUnits(), now)) {
			throw ApiException.conflict(QuoteService.SLOT_FULL,
					"This delivery slot just filled up. Please choose another slot.");
		}
		jdbc.update("""
				INSERT INTO slot_reservations (order_id, slot_id, reserved_units, status, expires_at)
				VALUES (?, ?, ?, ?::reservation_status, ?)""", orderId, cart.slot().id(), cart.capacityUnits(),
				cod ? "CONFIRMED" : "HELD", expiresAt);
		stock.reserve(orderId, cart.ingredientNeeds());
		if (cod) {
			stock.confirm(orderId);
		}

		for (PricedLine line : cart.lines()) {
			UUID itemId = jdbc.queryForObject("""
					INSERT INTO order_items (order_id, product_id, product_name, quantity, unit_base_price,
					                         unit_options_price, unit_price, line_total, production_units)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", UUID.class, orderId, line.productId(),
					line.productName(), line.quantity(),
					line.unitPrice().minus(line.customization().optionsTotal()).amount(),
					line.customization().optionsTotal().amount(), line.unitPrice().amount(), line.lineTotal().amount(),
					line.productionUnits());
			// Choices come in the product's display order (ADR-0021); the snapshot keeps it.
			List<Choice> choices = line.customization().choices();
			for (int position = 0; position < choices.size(); position++) {
				Choice choice = choices.get(position);
				jdbc.update("""
						INSERT INTO order_item_customizations
						  (order_item_id, group_id, option_id, group_name, option_name, text_value, price_delta,
						   display_order)
						VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", itemId, choice.groupId(), choice.optionId(), choice.groupName(),
						choice.optionName(), choice.textValue(), choice.priceDelta().amount(), position);
			}
		}

		jdbc.update("INSERT INTO payments (order_id, method, amount, status) VALUES (?, ?::payment_method, ?, 'PENDING')",
				orderId, request.paymentMethod().name(), cart.total().amount());
		jdbc.update("""
				INSERT INTO order_status_history (order_id, from_status, to_status, actor_type, actor_user_id)
				VALUES (?, NULL, ?::order_status, 'CUSTOMER', ?)""", orderId, status, userId);
		outbox.publish(OrderEvents.AGGREGATE, orderId, OrderEvents.PLACED, Map.of("orderId", orderId.toString()));
		return orderId;
	}

	/** {@code BB-YYYYMMDD-NNNNNN}: Dhaka date of placement and the database sequence. */
	private String orderNumber(Instant now) {
		Long next = jdbc.queryForObject("SELECT nextval('order_number_seq')", Long.class);
		return "BB-" + LocalDate.ofInstant(now, BusinessTime.ZONE).format(ORDER_DATE) + "-" + "%06d".formatted(next);
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

}
