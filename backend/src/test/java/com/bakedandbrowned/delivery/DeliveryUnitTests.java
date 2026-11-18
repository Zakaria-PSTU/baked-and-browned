package com.bakedandbrowned.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.bakedandbrowned.common.value.Money;
import com.bakedandbrowned.common.web.ApiException;
import com.bakedandbrowned.delivery.entity.DeliveryZone;
import com.bakedandbrowned.delivery.service.ZoneEligibility;
import com.bakedandbrowned.scheduling.service.LeadTime;

class DeliveryUnitTests {

	// ------------------------------------------------------------------ zone eligibility (ADR-0010)

	@Test
	void activeZoneWithFeeIsEligibleAndCarriesItsOwnFee() {
		ZoneEligibility.EligibleZone zone = ZoneEligibility.check(zone(true, "60.00", null), Money.of("100.00"));
		assertThat(zone.name()).isEqualTo("Dhanmondi");
		assertThat(zone.deliveryFee()).isEqualTo(Money.of("60.00"));
	}

	@Test
	void unknownInactiveAndFeelessZonesAreIndistinguishable() {
		for (DeliveryZone zone : new DeliveryZone[] { null, zone(false, "60.00", null), zone(false, null, null) }) {
			assertThatThrownBy(() -> ZoneEligibility.check(zone, Money.of("5000.00")))
				.isInstanceOfSatisfying(ApiException.class, ex -> {
					assertThat(ex.code()).isEqualTo(ZoneEligibility.ZONE_NOT_AVAILABLE);
					assertThat(ex.getMessage()).isEqualTo("We do not deliver to this area yet.");
				});
		}
	}

	@Test
	void minimumOrderAppliesToTheSubtotal() {
		DeliveryZone zone = zone(true, "80.00", "1500.00");
		assertThat(ZoneEligibility.check(zone, Money.of("1500.00")).deliveryFee()).isEqualTo(Money.of("80.00"));
		assertThatThrownBy(() -> ZoneEligibility.check(zone, Money.of("1499.99")))
			.isInstanceOfSatisfying(ApiException.class, ex -> {
				assertThat(ex.code()).isEqualTo(ZoneEligibility.BELOW_MINIMUM_ORDER);
				assertThat(ex.getMessage()).isEqualTo("The minimum order for Dhanmondi is BDT 1,500.00.");
			});
	}

	// ------------------------------------------------------------------ lead time

	@ParameterizedTest(name = "slot {1}h after now, lead {2}h -> {3}")
	@CsvSource({ "2026-11-18T06:00:00Z, 24, 24, true", "2026-11-18T06:00:00Z, 23, 24, false",
			"2026-11-18T06:00:00Z, 48, 48, true", "2026-11-18T06:00:00Z, 0, 0, true",
			"2026-11-18T06:00:00Z, 1, 0, true" })
	void slotMustStartAtLeastTheLeadTimeAfterNow(Instant now, long hoursAhead, int lead, boolean ok) {
		assertThat(LeadTime.satisfied(now, now.plusSeconds(hoursAhead * 3600), lead)).isEqualTo(ok);
	}

	@Test
	void oneSecondShortOfTheLeadTimeFails() {
		Instant now = Instant.parse("2026-11-18T06:00:00Z");
		assertThat(LeadTime.satisfied(now, now.plusSeconds(24 * 3600 - 1), 24)).isFalse();
	}

	// ------------------------------------------------------------------ money display

	@ParameterizedTest
	@CsvSource({ "0.00, BDT 0.00", "999.50, BDT 999.50", "1500, 'BDT 1,500.00'", "125000, 'BDT 1,25,000.00'",
			"12345678.90, 'BDT 1,23,45,678.90'" })
	void moneyDisplaysWithSouthAsianGrouping(String amount, String expected) {
		assertThat(Money.of(amount).toDisplayString()).isEqualTo(expected);
	}

	private static DeliveryZone zone(boolean active, String fee, String minimum) {
		return new DeliveryZone(new DeliveryZone.Details("DHANMONDI", "Dhanmondi", fee == null ? null : Money.of(fee),
				minimum == null ? null : Money.of(minimum), active, 10));
	}

}
