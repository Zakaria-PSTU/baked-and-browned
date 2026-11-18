package com.bakedandbrowned.spike;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.bakedandbrowned.IntegrationTest;

/**
 * Phase 1B spike: if these mappings were incompatible with the Flyway schema, the shared
 * context would fail to start under {@code ddl-auto=validate}. The tests then prove the
 * values round-trip correctly.
 */
@IntegrationTest
@Transactional
class HibernateMappingSpikeTests {

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void nativeEnumIsWrittenAsPostgresEnumAndReadBack() {
		UUID id = UUID.randomUUID();
		entityManager.persist(new SpikeUser(id, "Spike Admin", "+8801712345678", "x", SpikeUser.Role.ADMIN));
		entityManager.flush();
		entityManager.clear();

		assertThat(jdbc.queryForObject("SELECT role::text FROM users WHERE id = ?", String.class, id))
			.isEqualTo("ADMIN");
		assertThat(jdbc.queryForObject("SELECT pg_typeof(role)::text FROM users WHERE id = ?", String.class, id))
			.isEqualTo("user_role");

		SpikeUser reloaded = entityManager.find(SpikeUser.class, id);
		assertThat(reloaded.getRole()).isEqualTo(SpikeUser.Role.ADMIN);
	}

	@Test
	void timestamptzMapsToInstantInUtc() {
		UUID id = UUID.randomUUID();
		entityManager.persist(new SpikeUser(id, "Spike Customer", "+8801812345678", "x", SpikeUser.Role.CUSTOMER));
		entityManager.flush();
		entityManager.clear();

		Instant createdAt = entityManager.find(SpikeUser.class, id).getCreatedAt();
		Instant dbNow = jdbc.queryForObject("SELECT now()", Instant.class);
		assertThat(Duration.between(createdAt, dbNow).abs()).isLessThan(Duration.ofMinutes(1));
	}

	@Test
	void numericMoneyAndSeededZonesMap() {
		List<SpikeDeliveryZone> zones = entityManager
			.createQuery("SELECT z FROM SpikeDeliveryZone z ORDER BY z.code", SpikeDeliveryZone.class)
			.getResultList();

		assertThat(zones).extracting(SpikeDeliveryZone::getCode)
			.containsExactly("BANANI", "DHANMONDI", "GULSHAN", "MIRPUR", "MOHAMMADPUR", "UTTARA");
		assertThat(zones).allSatisfy(zone -> {
			assertThat(zone.isActive()).isFalse();
			assertThat(zone.getDeliveryFee()).isNull();
			assertThat(zone.getUpdatedAt()).isNotNull();
		});
	}

}
