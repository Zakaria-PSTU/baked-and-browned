package com.bakedandbrowned.reporting.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bakedandbrowned.reporting.dto.DashboardDtos.DashboardSummary;
import com.bakedandbrowned.reporting.service.DashboardQueries;

/** Contract tag {@code Admin}: the dashboard summary. ADMIN only, read-only. */
@RestController
@RequestMapping("/api/v1/admin/dashboard")
@PreAuthorize("hasRole('ADMIN')")
public class AdminDashboardController {

	private final DashboardQueries queries;

	AdminDashboardController(DashboardQueries queries) {
		this.queries = queries;
	}

	@GetMapping("/summary")
	DashboardSummary summary() {
		return queries.summary();
	}

}
