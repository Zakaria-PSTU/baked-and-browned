package com.bakedandbrowned.ordering.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.bakedandbrowned.common.value.Money;
import com.bakedandbrowned.customization.service.ValidatedCustomization;
import com.bakedandbrowned.ordering.service.PricedCart.PricedLine;

class PricedCartTest {

	private static final Instant SLOT_START = Instant.parse("2026-11-21T04:00:00Z");

	@Test
	void unitPriceIsBasePlusOptionDeltasAndLineIsQuantityTimesUnit() {
		PricedLine line = line("1250.00", "680.00", 3, 2, 24);
		assertThat(line.unitPrice()).isEqualTo(Money.of("1930.00"));
		assertThat(line.lineTotal()).isEqualTo(Money.of("5790.00"));
	}

	@Test
	void subtotalAndCapacitySumAllLines() {
		List<PricedLine> lines = List.of(line("1250.00", "600.00", 2, 2, 24), line("600.00", "0.00", 1, 1, 24));
		assertThat(PricedCart.subtotal(lines)).isEqualTo(Money.of("4300.00"));
		assertThat(PricedCart.capacityUnits(lines)).isEqualTo(5);
		assertThat(PricedCart.subtotal(List.of())).isEqualTo(Money.ZERO);
	}

	@Test
	void theStrictestCutoffSetsTheCancellationDeadline() {
		// Business rules acceptance 12: mixed 24 h and 48 h items use 48 h.
		List<PricedLine> mixed = List.of(line("1.00", "0.00", 1, 1, 24), line("1.00", "0.00", 1, 1, 48));
		assertThat(PricedCart.cancellationDeadline(SLOT_START, mixed)).isEqualTo(Instant.parse("2026-11-19T04:00:00Z"));
		assertThat(PricedCart.cancellationDeadline(SLOT_START, List.of(line("1.00", "0.00", 1, 1, 0))))
			.isEqualTo(SLOT_START);
	}

	private static PricedLine line(String base, String options, int quantity, int units, int cutoff) {
		return PricedLine.of(UUID.randomUUID(), "Item", Money.of(base), quantity,
				new ValidatedCustomization(List.of(), Money.of(options)), units, cutoff);
	}

}
