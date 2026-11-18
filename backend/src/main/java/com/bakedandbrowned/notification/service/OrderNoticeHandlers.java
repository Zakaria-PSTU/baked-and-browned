package com.bakedandbrowned.notification.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.bakedandbrowned.common.outbox.OutboxHandler;
import com.bakedandbrowned.common.outbox.OutboxMessage;
import com.bakedandbrowned.notification.service.OrderNotices.Kind;
import com.bakedandbrowned.ordering.service.OrderEvents;

/** One outbox handler per order event, each sending the matching customer notice. */
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
	OutboxHandler orderCancelledNotice(OrderNotificationDelivery delivery) {
		return new NoticeHandler(OrderEvents.CANCELLED, Kind.ORDER_CANCELLED, delivery);
	}

	@Bean
	OutboxHandler orderExpiredNotice(OrderNotificationDelivery delivery) {
		return new NoticeHandler(OrderEvents.EXPIRED, Kind.ORDER_EXPIRED, delivery);
	}

	private record NoticeHandler(String eventType, Kind kind, OrderNotificationDelivery delivery)
			implements OutboxHandler {

		@Override
		public void handle(OutboxMessage message) throws Exception {
			delivery.deliver(message, kind);
		}

	}

}
