package com.bakedandbrowned.common.json;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import tools.jackson.databind.json.JsonMapper;

/**
 * Phase 1A spike: how Boot 4's auto-configured Jackson 3 mapper handles BDT amounts.
 * These tests pin today's behaviour so the Money type built in Phase 1C can rely on it
 * (and so a Jackson upgrade that changes it fails loudly).
 */
@JsonTest
class MoneyJsonSerializationSpikeTest {

	record Amount(BigDecimal value) {
	}

	@Autowired
	private JsonMapper jsonMapper;

	@Test
	void bigDecimalWithScaleTwoIsWrittenWithTwoDecimals() {
		assertThat(jsonMapper.writeValueAsString(new Amount(new BigDecimal("1250.00"))))
			.isEqualTo("{\"value\":1250.00}");
	}

	@Test
	void bigDecimalWithoutScaleIsNotPadded() {
		// Finding: scale is NOT normalized by Jackson; Money must setScale(2) itself.
		assertThat(jsonMapper.writeValueAsString(new Amount(new BigDecimal("1250"))))
			.isEqualTo("{\"value\":1250}");
	}

	@Test
	void decimalInputIsReadExactlyWithoutBinaryFloatingPointError() {
		Amount amount = jsonMapper.readValue("{\"value\":0.10}", Amount.class);
		assertThat(amount.value()).isEqualByComparingTo("0.10");
		assertThat(amount.value().scale()).isEqualTo(2);
	}

	@Test
	void excessScaleIsAcceptedOnInput() {
		// Finding: Jackson does not reject 3+ decimals; request validation must (422).
		Amount amount = jsonMapper.readValue("{\"value\":10.005}", Amount.class);
		assertThat(amount.value().scale()).isEqualTo(3);
	}

}
