package com.bakedandbrowned.reporting.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bakedandbrowned.common.time.BusinessTime;
import com.bakedandbrowned.common.value.Money;
import com.bakedandbrowned.reporting.dto.DashboardDtos.DashboardSummary;

/**
 * The ADMIN dashboard figures, read in one statement (one consistent snapshot). "Today" is the
 * Asia/Dhaka calendar day containing {@code now}, as the half-open range [00:00, next 00:00).
 * <ul>
 * <li>{@code ordersToday}, {@code revenueToday}: booked orders placed today, those not in
 * PENDING_PAYMENT, CANCELLED or EXPIRED (ADR-0013), and the sum of their totals. Both use the same
 * set, so revenue divided by orders is the average booked order.</li>
 * <li>{@code collectedToday}: payments recorded as received today (verified_at for manual
 * approvals and COD cash collection, captured_at for gateway-verified payments), gross of refunds.</li>
 * <li>{@code pendingOrders}: CONFIRMED orders waiting for the kitchen; {@code preparingOrders}:
 * PREPARING. Any day.</li>
 * <li>{@code awaitingPaymentVerification}: the size of the ADMIN verification queue (MANUAL
 * payments whose latest submission is SUBMITTED), matching the payments list filter.</li>
 * <li>{@code openReconciliationCases}: OPEN cases.</li>
 * <li>{@code lowStockIngredients}: ingredients with a threshold whose available stock (on hand
 * minus reserved) is at or below it.</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class DashboardQueries {

	private static final String SUMMARY = """
			SELECT
			  (SELECT count(*) FROM orders o
			   WHERE o.created_at >= ? AND o.created_at < ?
			     AND o.status NOT IN ('PENDING_PAYMENT', 'CANCELLED', 'EXPIRED')) AS orders_today,
			  (SELECT coalesce(sum(o.total_amount), 0) FROM orders o
			   WHERE o.created_at >= ? AND o.created_at < ?
			     AND o.status NOT IN ('PENDING_PAYMENT', 'CANCELLED', 'EXPIRED')) AS revenue_today,
			  (SELECT count(*) FROM orders WHERE status = 'CONFIRMED') AS pending_orders,
			  (SELECT count(*) FROM orders WHERE status = 'PREPARING') AS preparing_orders,
			  (SELECT coalesce(sum(p.amount), 0) FROM payments p
			   WHERE coalesce(p.verified_at, p.captured_at) >= ? AND coalesce(p.verified_at, p.captured_at) < ?
			     AND p.status IN ('SUCCEEDED', 'REFUNDED')) AS collected_today,
			  (SELECT count(*) FROM payments p
			   JOIN LATERAL (SELECT s.status FROM manual_payment_submissions s WHERE s.payment_id = p.id
			                 ORDER BY s.created_at DESC, s.id DESC LIMIT 1) m ON TRUE
			   WHERE p.method = 'MANUAL' AND m.status = 'SUBMITTED') AS awaiting_verification,
			  (SELECT count(*) FROM payment_reconciliation_cases WHERE status = 'OPEN') AS open_cases,
			  (SELECT count(*) FROM ingredients
			   WHERE low_stock_threshold IS NOT NULL AND on_hand - reserved <= low_stock_threshold) AS low_stock""";

	private final JdbcTemplate jdbc;

	private final Clock clock;

	public DashboardQueries(JdbcTemplate jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	public DashboardSummary summary() {
		return summary(clock.instant());
	}

	/** The summary as of {@code now}; separate so the day boundaries can be tested. */
	public DashboardSummary summary(Instant now) {
		LocalDate today = LocalDate.ofInstant(now, BusinessTime.ZONE);
		Timestamp from = Timestamp.from(today.atStartOfDay(BusinessTime.ZONE).toInstant());
		Timestamp to = Timestamp.from(today.plusDays(1).atStartOfDay(BusinessTime.ZONE).toInstant());
		DashboardSummary summary = jdbc.queryForObject(SUMMARY,
				(rs, row) -> new DashboardSummary(rs.getInt("orders_today"), Money.of(rs.getBigDecimal("revenue_today")),
						rs.getInt("pending_orders"), rs.getInt("preparing_orders"),
						Money.of(rs.getBigDecimal("collected_today")), rs.getInt("awaiting_verification"),
						rs.getInt("open_cases"), rs.getInt("low_stock")),
				from, to, from, to, from, to);
		if (summary == null) {
			throw new IllegalStateException("The dashboard query returned no row");
		}
		return summary;
	}

}
