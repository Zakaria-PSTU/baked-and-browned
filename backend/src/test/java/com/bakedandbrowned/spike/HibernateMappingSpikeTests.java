package com.bakedandbrowned.spike;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.bakedandbrowned.IntegrationTest;

/**
 * Phase 1B spike: if these mappings were incompatible with the Flyway schema, the shared
 * context would fail to start under {@code ddl-auto=validate}. The tests then prove the
 * values round-trip correctly. The user mappings moved to the real entity in
 * Phase 1D ({@code UserAccountMappingTests}).
 */
@IntegrationTest
@Transactional
class HibernateMappingSpikeTests {

	@Autowired
	private EntityManager entityManager;

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
