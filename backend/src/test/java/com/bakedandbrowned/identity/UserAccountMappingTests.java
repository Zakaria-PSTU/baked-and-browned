package com.bakedandbrowned.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.bakedandbrowned.common.value.PhoneNumber;
import com.bakedandbrowned.identity.entity.RefreshToken;
import com.bakedandbrowned.identity.entity.Role;
import com.bakedandbrowned.identity.entity.UserAccount;

import jakarta.persistence.EntityManager;

/**
 * The identity entities against the Flyway schema (validated at context start under
 * {@code ddl-auto=validate}), replacing the Phase 1B {@code SpikeUser}: native {@code user_role}
 * enum, TIMESTAMPTZ instants and the {@code CHAR(64)} token hash round-trip.
 */
@Transactional
class UserAccountMappingTests extends IdentityIntegrationTest {

	@Autowired
	private EntityManager entityManager;

	@Test
	void nativeEnumAndDatabaseTimestampsRoundTrip() {
		UserAccount admin = UserAccount.create("Mapping Admin", null, PhoneNumber.of(phone()), "hash", Role.ADMIN);
		entityManager.persist(admin);
		entityManager.flush();
		entityManager.clear();

		UUID id = admin.getId();
		assertThat(jdbc.queryForObject("SELECT pg_typeof(role)::text || ':' || role::text FROM users WHERE id = ?",
				String.class, id))
			.isEqualTo("user_role:ADMIN");
		UserAccount reloaded = entityManager.find(UserAccount.class, id);
		assertThat(reloaded.getRole()).isEqualTo(Role.ADMIN);
		assertThat(reloaded.isEnabled()).isTrue();
		Instant dbNow = jdbc.queryForObject("SELECT now()", Instant.class);
		assertThat(Duration.between(reloaded.getCreatedAt(), dbNow).abs()).isLessThan(Duration.ofMinutes(1));
	}

	@Test
	void refreshTokenHashIsStoredInCharColumn() {
		UserAccount user = UserAccount.create("Mapping User", null, PhoneNumber.of(phone()), "hash", Role.CUSTOMER);
		entityManager.persist(user);
		String hash = "a".repeat(64);
		RefreshToken token = RefreshToken.issue(user, hash, UUID.randomUUID(), Instant.now().plusSeconds(60),
				Instant.now().plusSeconds(120), 0);
		entityManager.persist(token);
		entityManager.flush();
		entityManager.clear();

		assertThat(jdbc.queryForObject("SELECT token_hash FROM refresh_tokens WHERE id = ?", String.class, token.getId()))
			.isEqualTo(hash);
		RefreshToken reloaded = entityManager.find(RefreshToken.class, token.getId());
		assertThat(reloaded.isUsed()).isFalse();
		assertThat(reloaded.isRevoked()).isFalse();
	}

}
