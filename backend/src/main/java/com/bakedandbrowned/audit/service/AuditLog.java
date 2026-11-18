package com.bakedandbrowned.audit.service;

import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.bakedandbrowned.common.web.TraceIdFilter;

import tools.jackson.databind.json.JsonMapper;

/**
 * Appends to {@code audit_events} (append-only: a trigger rejects UPDATE and DELETE). Called inside
 * the business transaction ({@code MANDATORY}), so an administrative change and its audit record
 * commit or roll back together. The request's trace id is recorded for correlation with logs.
 * <p>
 * {@code details} must not contain secrets or more personal data than the decision needs.
 */
@Service
public class AuditLog {

	private final JdbcTemplate jdbc;

	private final JsonMapper json;

	public AuditLog(JdbcTemplate jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	/** An administrative action by the ADMIN user {@code actorUserId}. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void admin(UUID actorUserId, String action, String entityType, @Nullable UUID entityId,
			@Nullable String reason, Map<String, ?> details) {
		jdbc.update("""
				INSERT INTO audit_events (actor_type, actor_user_id, action, entity_type, entity_id, reason, details, trace_id)
				VALUES ('ADMIN', ?, ?, ?, ?, ?, ?::jsonb, ?)""", actorUserId, action, entityType, entityId, reason,
				json.writeValueAsString(details), MDC.get(TraceIdFilter.MDC_KEY));
	}

	/** A decision taken on a payment gateway's server-to-server verified result (actor GATEWAY). */
	@Transactional(propagation = Propagation.MANDATORY)
	public void gateway(String action, String entityType, @Nullable UUID entityId, Map<String, ?> details) {
		jdbc.update("""
				INSERT INTO audit_events (actor_type, action, entity_type, entity_id, details, trace_id)
				VALUES ('GATEWAY', ?, ?, ?, ?::jsonb, ?)""", action, entityType, entityId,
				json.writeValueAsString(details), MDC.get(TraceIdFilter.MDC_KEY));
	}

}
