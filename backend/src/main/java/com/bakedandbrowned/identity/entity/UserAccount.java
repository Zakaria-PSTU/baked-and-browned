package com.bakedandbrowned.identity.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

import com.bakedandbrowned.common.value.PhoneNumber;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A row of {@code users}. Never leaves the identity module's service layer: controllers return DTOs.
 * {@code created_at}/{@code updated_at} are database-maintained (default and trigger).
 */
@Entity
@Table(name = "users")
public class UserAccount {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "full_name", nullable = false, length = 150)
	private String fullName;

	@Column(length = 255)
	private @Nullable String email;

	@Column(nullable = false, length = 20)
	private String phone;

	@Column(name = "password_hash", nullable = false)
	private String passwordHash;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(nullable = false)
	private Role role;

	@Column(nullable = false)
	private boolean enabled;

	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
	private Instant updatedAt;

	protected UserAccount() {
	}

	private UserAccount(String fullName, @Nullable String email, PhoneNumber phone, String passwordHash, Role role) {
		this.fullName = fullName;
		this.email = email;
		this.phone = phone.value();
		this.passwordHash = passwordHash;
		this.role = role;
		this.enabled = true;
	}

	/** A new enabled account; {@code passwordHash} is already Argon2id-encoded. */
	public static UserAccount create(String fullName, @Nullable String email, PhoneNumber phone, String passwordHash,
			Role role) {
		return new UserAccount(fullName, email, phone, passwordHash, role);
	}

	public UUID getId() {
		return id;
	}

	public String getFullName() {
		return fullName;
	}

	public @Nullable String getEmail() {
		return email;
	}

	public PhoneNumber getPhone() {
		return PhoneNumber.of(phone);
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public Role getRole() {
		return role;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	/** Never include the hash, email or full phone number. */
	@Override
	public String toString() {
		return "UserAccount[id=" + id + ", role=" + role + ", enabled=" + enabled + "]";
	}

}
