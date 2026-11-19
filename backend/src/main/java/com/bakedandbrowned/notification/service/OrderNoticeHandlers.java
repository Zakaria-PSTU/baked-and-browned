package com.bakedandbrowned.notification.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.bakedandbrowned.common.outbox.OutboxHandler;
import com.bakedandbrowned.common.outbox.OutboxMessage;
import com.bakedandbrowned.notification.service.OrderNotices.Kind;
import com.bakedandbrowned.ordering.service.OrderEvents;
import com.bakedandbrowned.payment.service.PaymentEvents;

/** One outbox handler per order or refund event, each sending the matching customer notice. */
// Not in the provision-admin command (ADR-0024): it sends no notices, so it needs no SmsSender.
@Profile("!provision-admin")
@Configuration(proxyBeanMethods = false)
class OrderNoticeHandlers {

	@Bean
	OutboxHandler orderPlacedNotice(OrderNotificationDelivery delivery) {
		return new NoticeHandler(OrderEvents.PLACED, Kind.ORDER_RECEIVED, delivery);
	}

	@Bean
	OutboxHandler orderConfirmedNotice(OrderNotificationDelivery delivery) {
		return new NoticeHandler(OrderEvents.CONFIRMED, Kind.ORDER_CONFIRMED, delivery);
	}

	@Bean
	OutboxHandler orderDispatchedNotice(OrderNotificationDelivery delivery) {
		return new NoticeHandler(OrderEvents.DISPATCHED, Kind.ORDER_DISPATCHED, delivery);
	}

	@Bean
	OutboxHandler orderCancelledNotice(OrderNotificationDelivery delivery) {
		return new NoticeHandler(OrderEvents.CANCELLED, Kind.ORDER_CANCELLED, delivery);
	}

	@Bean
	OutboxHandler orderExpiredNotice(OrderNotificationDelivery delivery) {
		return new NoticeHandler(OrderEvents.EXPIRED, Kind.ORDER_EXPIRED, delivery);
	}

	@Bean
	OutboxHandler refundCompletedNotice(OrderNotificationDelivery delivery) {
		return new NoticeHandler(PaymentEvents.REFUND_COMPLETED, Kind.REFUND_COMPLETED, delivery);
	}

	private record NoticeHandler(String eventType, Kind kind, OrderNotificationDelivery delivery)
			implements OutboxHandler {

		@Override
		public void handle(OutboxMessage message) throws Exception {
			delivery.deliver(message, kind);
		}

	}

}
