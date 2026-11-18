package com.bakedandbrowned.common.value;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.databind.json.JsonMapper;

class QuantityTest {

	@Test
	void normalizesToThreeDecimals() {
		assertThat(Quantity.of("2.5").value()).isEqualTo(new BigDecimal("2.500"));
		assertThat(Quantity.of("1.2500").toString()).isEqualTo("1.250");
		assertThat(Quantity.of("0")).isEqualTo(Quantity.ZERO);
	}

	@ParameterizedTest
	@ValueSource(strings = { "-0.001", "0.0001", "1.2345", "100000000000" })
	void rejectsNegativeOverPreciseAndOversizedValues(String raw) {
		assertThatThrownBy(() -> Quantity.of(raw)).isInstanceOf(InvalidValueException.class);
	}

	@Test
	void recipeArithmeticIsExact() {
		Quantity perBox = Quantity.of("0.125");
		assertThat(perBox.times(3).plus(Quantity.of("0.625"))).isEqualTo(Quantity.of("1.000"));
		assertThat(Quantity.of("1.000").minus(Quantity.of("0.375"))).isEqualTo(Quantity.of("0.625"));
		assertThatIllegalArgumentException().isThrownBy(() -> Quantity.of("0.1").minus(Quantity.of("0.2")));
	}

	@Test
	void jsonIsANumberWithThreeDecimals() {
		JsonMapper json = JsonMapper.builder().build();
		assertThat(json.writeValueAsString(Quantity.of("2.5"))).isEqualTo("2.500");
		assertThat(json.readValue("0.75", Quantity.class)).isEqualTo(Quantity.of("0.750"));
		assertThatThrownBy(() -> json.readValue("0.7505", Quantity.class))
			.hasRootCauseInstanceOf(InvalidValueException.class);
	}

}
