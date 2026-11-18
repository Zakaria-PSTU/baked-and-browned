package com.bakedandbrowned.payment.controller;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bakedandbrowned.payment.dto.PaymentDtos.ReconciliationCase;
import com.bakedandbrowned.payment.dto.PaymentDtos.ReconciliationResolutionRequest;
import com.bakedandbrowned.payment.service.ReconciliationCases;

import jakarta.validation.Valid;

/** Contract tag {@code Admin}: payment reconciliation cases. ADMIN only. */
@RestController
@RequestMapping("/api/v1/admin/payment-reconciliation-cases")
@PreAuthorize("hasRole('ADMIN')")
public class AdminReconciliationController {

	private final ReconciliationCases cases;

	AdminReconciliationController(ReconciliationCases cases) {
		this.cases = cases;
	}

	@GetMapping
	List<ReconciliationCase> list(@RequestParam(name = "status", required = false) @Nullable CaseStatus status) {
		return cases.list(status == null ? null : status.name());
	}

	@PostMapping("/{caseId}/resolution")
	ReconciliationCase resolve(@AuthenticationPrincipal Jwt jwt, @PathVariable("caseId") UUID caseId,
			@Valid @RequestBody ReconciliationResolutionRequest body) {
		return cases.resolve(caseId, UUID.fromString(jwt.getSubject()), body.action(), body.note());
	}

	/** Contract {@code ReconciliationCase.status} (query filter). */
	enum CaseStatus {

		OPEN, RESOLVED

	}

}
