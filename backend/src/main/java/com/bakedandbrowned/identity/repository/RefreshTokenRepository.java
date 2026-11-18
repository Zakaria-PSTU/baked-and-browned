package com.bakedandbrowned.identity.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bakedandbrowned.identity.entity.RefreshToken;
import com.bakedandbrowned.identity.entity.RevokeReason;

import jakarta.persistence.LockModeType;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

	/**
	 * {@code SELECT ... FOR UPDATE}: concurrent refreshes with the same token are serialized, so
	 * exactly one rotates it and the other sees it as used (reuse).
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select t from RefreshToken t join fetch t.user where t.tokenHash = :hash")
	Optional<RefreshToken> findByTokenHashForUpdate(@Param("hash") String hash);

	Optional<RefreshToken> findByTokenHash(String hash);

	/** Revokes every still-active token of the family; returns how many were revoked. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update RefreshToken t set t.revokedAt = :now, t.revokeReason = :reason
			where t.familyId = :familyId and t.revokedAt is null""")
	int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now,
			@Param("reason") RevokeReason reason);

}
