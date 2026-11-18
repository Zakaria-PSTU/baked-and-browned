package com.bakedandbrowned.reporting.dto;

import com.bakedandbrowned.common.value.Money;

/** API shapes for the ADMIN dashboard (contract tag Admin). */
public final class DashboardDtos {

	private DashboardDtos() {
	}

	/**
	 * Contract {@code adminDashboardSummary} 200 body. "Today" is the current Asia/Dhaka calendar
	 * day; the other counts are current totals regardless of day. Definitions are on
	 * {@code DashboardQueries}.
	 */
	public record DashboardSummary(int ordersToday, Money revenueToday, int pendingOrders, int preparingOrders,
			Money collectedToday, int awaitingPaymentVerification, int openReconciliationCases,
			int lowStockIngredients) {
	}

}
