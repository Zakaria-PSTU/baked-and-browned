package com.bakedandbrowned.identity.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One opaque refresh token in a rotation family. Only the SHA-256 hex hash is stored. A token is
 * usable while it is unused, unrevoked and unexpired; rotating it records {@code used_at} and
 * {@code replaced_by_id}. Presenting a used token again is reuse: the whole family is revoked.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false, updatable = false)
	private UserAccount user;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "token_hash", nullable = false, length = 64, updatable = false)
	private String tokenHash;

	@Column(name = "family_id", nullable = false, updatable = false)
	private UUID familyId;

	@Column(name = "expires_at", nullable = false, updatable = false)
	private Instant expiresAt;

	/** The family's absolute end (ADR-0025): set at login, copied to every successor. */
	@Column(name = "family_expires_at", nullable = false, updatable = false)
	private Instant familyExpiresAt;

	@Column(name = "revoked_at")
	private @Nullable Instant revokedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "revoke_reason", length = 40)
	private @Nullable RevokeReason revokeReason;

	@Column(name = "used_at")
	private @Nullable Instant usedAt;

	@Column(name = "replaced_by_id")
	private @Nullable UUID replacedById;

	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private Instant createdAt;

	protected RefreshToken() {
	}

	private RefreshToken(UserAccount user, String tokenHash, UUID familyId, Instant expiresAt,
			Instant familyExpiresAt) {
		if (expiresAt.isAfter(familyExpiresAt)) {
			throw new IllegalArgumentException("a refresh token must not outlive its family");
		}
		this.user = user;
		this.tokenHash = tokenHash;
		this.familyId = familyId;
		this.expiresAt = expiresAt;
		this.familyExpiresAt = familyExpiresAt;
	}

	public static RefreshToken issue(UserAccount user, String tokenHash, UUID familyId, Instant expiresAt,
			Instant familyExpiresAt) {
		return new RefreshToken(user, tokenHash, familyId, expiresAt, familyExpiresAt);
	}

	public boolean isRevoked() {
		return revokedAt != null;
	}

	public boolean isUsed() {
		return usedAt != null;
	}

	public boolean isExpiredAt(Instant now) {
		return !now.isBefore(expiresAt);
	}

	/** Rotation: this token is spent and replaced by {@code successor}. */
	public void markReplaced(Instant now, UUID successor) {
		this.usedAt = now;
		this.replacedById = successor;
	}

	public UUID getId() {
		return id;
	}

	public UserAccount getUser() {
		return user;
	}

	public UUID getFamilyId() {
		return familyId;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public Instant getFamilyExpiresAt() {
		return familyExpiresAt;
	}

	public @Nullable Instant getRevokedAt() {
		return revokedAt;
	}

	public @Nullable RevokeReason getRevokeReason() {
		return revokeReason;
	}

	public @Nullable Instant getUsedAt() {
		return usedAt;
	}

	public @Nullable UUID getReplacedById() {
		return replacedById;
	}

	@Override
	public String toString() {
		return "RefreshToken[id=" + id + ", familyId=" + familyId + "]";
	}

}
