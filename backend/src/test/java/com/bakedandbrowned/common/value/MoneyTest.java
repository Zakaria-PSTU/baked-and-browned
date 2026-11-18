package com.bakedandbrowned.common.value;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyTest {

	@ParameterizedTest
	@ValueSource(strings = { "1250", "1250.0", "1250.00", "1250.000" })
	void normalizesEquivalentAmountsToScaleTwo(String input) {
		Money money = Money.of(input);
		assertThat(money.amount()).isEqualTo(new BigDecimal("1250.00"));
		assertThat(money).isEqualTo(Money.of("1250.00")).hasSameHashCodeAs(Money.of("1250.00"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "10.005", "0.001", "99.999" })
	void rejectsMoreThanTwoDecimals(String input) {
		assertThatThrownBy(() -> Money.of(input)).isInstanceOf(InvalidValueException.class)
			.hasMessage("amount must have at most 2 decimal places");
	}

	@Test
	void rejectsNegativeAndMissingAmounts() {
		assertThatThrownBy(() -> Money.of("-0.01")).isInstanceOf(InvalidValueException.class)
			.hasMessage("amount must not be negative");
		assertThatThrownBy(() -> Money.of((BigDecimal) null)).isInstanceOf(InvalidValueException.class);
	}

	@Test
	void enforcesNumeric12Comma2Range() {
		assertThat(Money.of("9999999999.99").amount()).isEqualTo(new BigDecimal("9999999999.99"));
		assertThatThrownBy(() -> Money.of("10000000000.00")).isInstanceOf(InvalidValueException.class)
			.hasMessage("amount exceeds the maximum supported value");
		assertThatThrownBy(() -> Money.of("9999999999.99").plus(Money.of("0.01")))
			.isInstanceOf(InvalidValueException.class);
	}

	@Test
	void pricingArithmeticIsExact() {
		// business-rules.md: unit_price = base + deltas; line_total = qty * unit_price; total = subtotal + fee - discount.
		Money unitPrice = Money.of("850.00").plus(Money.of("120.50"));
		Money lineTotal = unitPrice.times(3);
		Money total = lineTotal.plus(Money.of("60.00")).minus(Money.ZERO);
		assertThat(unitPrice).isEqualTo(Money.of("970.50"));
		assertThat(lineTotal).isEqualTo(Money.of("2911.50"));
		assertThat(total.amount()).isEqualTo(new BigDecimal("2971.50"));
		assertThat(Money.of("0.10").plus(Money.of("0.20"))).isEqualTo(Money.of("0.30"));
	}

	@Test
	void serverSideArithmeticBugsAreNotReportedAsClientErrors() {
		assertThatThrownBy(() -> Money.of("10.00").minus(Money.of("10.01")))
			.isExactlyInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> Money.of("10.00").times(-1)).isExactlyInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void zeroOrderingAndText() {
		assertThat(Money.ZERO.isZero()).isTrue();
		assertThat(Money.ZERO.amount().scale()).isEqualTo(2);
		assertThat(Money.of("5").times(0)).isEqualTo(Money.ZERO);
		assertThat(Money.of("1.00")).isLessThan(Money.of("1.01"));
		assertThat(Money.of("1250")).hasToString("1250.00");
	}

}