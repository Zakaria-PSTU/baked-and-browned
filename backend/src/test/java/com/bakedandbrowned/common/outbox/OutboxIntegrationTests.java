package com.bakedandbrowned.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import com.bakedandbrowned.IntegrationTest;

/**
 * Publish-in-transaction, claim with SKIP LOCKED, deliver outside the transaction, record success
 * or backoff. Dispatchers are built here with test handlers so the shared context is reused.
 */
@IntegrationTest
class OutboxIntegrationTests {

	private static final OutboxProperties PROPERTIES = new OutboxProperties(5, Duration.ofMinutes(5),
			Duration.ofSeconds(30), Duration.ofHours(1));

	@Autowired
	private OutboxPublisher publisher;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TransactionTemplate transactions;

	@BeforeEach
	@AfterEach
	void clearOutbox() {
		jdbc.execute("TRUNCATE outbox_events");
	}

	@Test
	void publishingRequiresTheCallersTransactionAndRollsBackWithIt() {
		assertThatThrownBy(() -> publisher.publish("order", UUID.randomUUID(), "order.confirmed", Map.of()))
			.isInstanceOf(IllegalTransactionStateException.class);

		transactions.executeWithoutResult(status -> {
			publisher.publish("order", UUID.randomUUID(), "order.confirmed", Map.of("orderId", "x"));
			status.setRollbackOnly();
		});
		assertThat(count("TRUE")).isZero();
	}

	@Test
	void deliversDueEventsOnceAndMarksThemProcessed() {
		UUID aggregate = UUID.randomUUID();
		UUID eventId = publish(aggregate, "order.confirmed", Map.of("orderId", aggregate.toString()));
		List<OutboxMessage> received = new ArrayList<>();
		OutboxDispatcher dispatcher = dispatcher(handler("order.confirmed", received::add));

		assertThat(dispatcher.dispatchDue()).isEqualTo(1);
		assertThat(dispatcher.dispatchDue()).isZero();

		assertThat(received).singleElement().satisfies(message -> {
			assertThat(message.id()).isEqualTo(eventId);
			assertThat(message.aggregateType()).isEqualTo("order");
			assertThat(message.aggregateId()).isEqualTo(aggregate);
			assertThat(message.attempt()).isEqualTo(1);
			assertThat(message.payload()).contains("\"orderId\"").contains(aggregate.toString());
		});
		assertThat(count("processed_at IS NOT NULL AND last_error IS NULL AND attempts = 1")).isEqualTo(1);
		assertThat(dispatcher.pendingCount()).isZero();
	}

	@Test
	void failuresAreRecordedAndRetriedWithBackoff() {
		publish(UUID.randomUUID(), "order.confirmed", Map.of());
		AtomicInteger calls = new AtomicInteger();
		OutboxDispatcher dispatcher = dispatcher(handler("order.confirmed", message -> {
			if (calls.incrementAndGet() == 1) {
				throw new IllegalStateException("SMS provider unavailable");
			}
		}));

		assertThat(dispatcher.dispatchDue()).isZero();
		Map<String, Object> row = jdbc.queryForMap("""
				SELECT attempts, last_error, processed_at,
				       extract(epoch FROM next_attempt_at - now())::int AS due_in
				FROM outbox_events""");
		assertThat(row.get("attempts")).isEqualTo(1);
		assertThat((String) row.get("last_error")).contains("IllegalStateException", "SMS provider unavailable");
		assertThat(row.get("processed_at")).isNull();
		assertThat((Integer) row.get("due_in")).isBetween(25, 30);

		// Not due yet: nothing is claimed.
		assertThat(dispatcher.dispatchDue()).isZero();
		assertThat(calls).hasValue(1);

		jdbc.update("UPDATE outbox_events SET next_attempt_at = now() - interval '1 second'");
		assertThat(dispatcher.dispatchDue()).isEqualTo(1);
		assertThat(count("processed_at IS NOT NULL AND last_error IS NULL AND attempts = 2")).isEqualTo(1);
	}

	@Test
	void aBatchIsDeliveredInTheOrderTheEventsWereCreated() {
		UUID aggregate = UUID.randomUUID();
		List<UUID> published = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			published.add(publish(aggregate, "order.confirmed", Map.of("n", i)));
		}
		List<UUID> received = new ArrayList<>();
		OutboxDispatcher dispatcher = dispatcher(handler("order.confirmed", message -> received.add(message.id())));

		// Four batches of five: within and across batches, oldest first.
		for (int batch = 0; batch < 4; batch++) {
			assertThat(dispatcher.dispatchDue()).isEqualTo(5);
		}

		assertThat(received).containsExactlyElementsOf(published);
	}

	@Test
	void eventsWithoutAHandlerStayPendingWithAnError() {
		publish(UUID.randomUUID(), "unknown.event", Map.of());

		assertThat(dispatcher().dispatchDue()).isZero();
		assertThat(count("processed_at IS NULL AND last_error LIKE '%No OutboxHandler for event type unknown.event%'"))
			.isEqualTo(1);
	}

	@Test
	void anExpiredLeaseMakesAnUnfinishedEventDueAgain() {
		publish(UUID.randomUUID(), "order.confirmed", Map.of());
		// As if a dispatcher claimed it (attempt 1) and died before recording the outcome.
		jdbc.update("UPDATE outbox_events SET attempts = 1, next_attempt_at = now() - interval '1 second'");
		List<OutboxMessage> received = new ArrayList<>();

		assertThat(dispatcher(handler("order.confirmed", received::add)).dispatchDue()).isEqualTo(1);
		assertThat(received).singleElement().extracting(OutboxMessage::attempt).isEqualTo(2);
	}

	@Test
	void concurrentDispatchersNeverClaimTheSameEvent() throws Exception {
		int events = 40;
		for (int i = 0; i < events; i++) {
			publish(UUID.randomUUID(), "order.confirmed", Map.of());
		}
		Map<UUID, AtomicInteger> deliveries = new ConcurrentHashMap<>();
		OutboxHandler slow = handler("order.confirmed", message -> {
			deliveries.computeIfAbsent(message.id(), id -> new AtomicInteger()).incrementAndGet();
			Thread.sleep(5);
		});
		int workers = 4;
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Integer>> results = new ArrayList<>();
		try (ExecutorService pool = Executors.newFixedThreadPool(workers)) {
			for (int w = 0; w < workers; w++) {
				OutboxDispatcher dispatcher = dispatcher(slow);
				results.add(pool.submit(() -> {
					start.await();
					int delivered = 0;
					for (int run = dispatcher.dispatchDue(); run > 0; run = dispatcher.dispatchDue()) {
						delivered += run;
					}
					return delivered;
				}));
			}
			start.countDown();
			int total = 0;
			for (Future<Integer> result : results) {
				total += result.get(60, TimeUnit.SECONDS);
			}
			assertThat(total).isEqualTo(events);
		}
		assertThat(deliveries).hasSize(events).allSatisfy((id, times) -> assertThat(times).hasValue(1));
		assertThat(count("processed_at IS NOT NULL AND attempts = 1")).isEqualTo(events);
	}

	// ------------------------------------------------------------------ helpers

	private UUID publish(UUID aggregate, String type, Object payload) {
		return transactions.execute(status -> publisher.publish("order", aggregate, type, payload));
	}

	private OutboxDispatcher dispatcher(OutboxHandler... handlers) {
		return new OutboxDispatcher(jdbc, PROPERTIES, List.of(handlers));
	}

	private int count(String where) {
		Integer count = jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE " + where, Integer.class);
		return count == null ? 0 : count;
	}

	@FunctionalInterface
	interface Delivery {

		void accept(OutboxMessage message) throws Exception;

	}

	private static OutboxHandler handler(String type, Delivery delivery) {
		return new OutboxHandler() {

			@Override
			public String eventType() {
				return type;
			}

			@Override
			public void handle(OutboxMessage message) throws Exception {
				delivery.accept(message);
			}

		};
	}

}
