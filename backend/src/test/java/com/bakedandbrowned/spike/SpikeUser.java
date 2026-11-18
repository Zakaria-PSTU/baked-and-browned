package com.bakedandbrowned.spike;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Phase 1B spike (test sources only): proves how a PostgreSQL native enum column
 * ({@code users.role user_role}) maps under {@code ddl-auto=validate}. Replaced by the real
 * identity entity in Phase 1D; delete this package then.
 */
@Entity
@Table(name = "users")
public class SpikeUser {

	public enum Role {
		CUSTOMER, ADMIN
	}

	@Id
	private UUID id;

	@Column(name = "full_name", nullable = false, length = 150)
	private String fullName;

	@Column(nullable = false, length = 20)
	private String phone;

	@Column(name = "password_hash", nullable = false)
	private String passwordHash;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(nullable = false)
	private Role role;

	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private Instant createdAt;

	protected SpikeUser() {
	}

	public SpikeUser(UUID id, String fullName, String phone, String passwordHash, Role role) {
		this.id = id;
		this.fullName = fullName;
		this.phone = phone;
		this.passwordHash = passwordHash;
		this.role = role;
	}

	public UUID getId() {
		return id;
	}

	public Role getRole() {
		return role;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
