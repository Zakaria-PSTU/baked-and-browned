package com.bakedandbrowned.common.web;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Gives every request a fresh trace id, for log lines (MDC key {@code traceId}) and for the
 * problem+json {@code traceId} field, so a customer-reported error can be found in the logs.
 * An inbound id is never trusted, so a caller cannot inject values into logs. Runs first, so later
 * filters (security, from Phase 1D) log with the id.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

	public static final String MDC_KEY = "traceId";

	static final String ATTRIBUTE = TraceIdFilter.class.getName() + ".traceId";

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String traceId = UUID.randomUUID().toString().replace("-", "");
		request.setAttribute(ATTRIBUTE, traceId);
		MDC.put(MDC_KEY, traceId);
		try {
			chain.doFilter(request, response);
		}
		finally {
			MDC.remove(MDC_KEY);
		}
	}

	/** The current request's trace id, or {@code null} outside a filtered request. */
	public static String traceId(HttpServletRequest request) {
		return request.getAttribute(ATTRIBUTE) instanceof String id ? id : null;
	}

}