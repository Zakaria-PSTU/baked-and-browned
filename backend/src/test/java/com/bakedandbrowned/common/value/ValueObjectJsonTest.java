package com.bakedandbrowned.common.value;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Money and PhoneNumber through Boot's auto-configured Jackson 3 mapper. */
@JsonTest
class ValueObjectJsonTest {

	record Priced(Money price, PhoneNumber phone) {
	}

	@Autowired
	private JsonMapper jsonMapper;

	@Test
	void moneyIsAlwaysWrittenWithExactlyTwoDecimals() {
		assertThat(jsonMapper.writeValueAsString(Money.of("1250"))).isEqualTo("1250.00");
		assertThat(jsonMapper.writeValueAsString(Money.ZERO)).isEqualTo("0.00");
		assertThat(jsonMapper.writeValueAsString(Money.of("0.5"))).isEqualTo("0.50");
		assertThat(jsonMapper.writeValueAsString(Money.of("9999999999.99"))).isEqualTo("9999999999.99");
	}

	@Test
	void roundTripsInsideARecord() {
		String json = "{\"price\":120.5,\"phone\":\"+8801712345678\"}";
		Priced priced = jsonMapper.readValue(json, Priced.class);
		assertThat(priced.price()).isEqualTo(Money.of("120.50"));
		assertThat(priced.phone().value()).isEqualTo("+8801712345678");
		assertThat(jsonMapper.writeValueAsString(priced))
			.isEqualTo("{\"price\":120.50,\"phone\":\"+8801712345678\"}");
	}

	@Test
	void nullStaysNullSoBeanValidationCanReportIt() {
		Priced priced = jsonMapper.readValue("{\"price\":null,\"phone\":null}", Priced.class);
		assertThat(priced.price()).isNull();
		assertThat(priced.phone()).isNull();
	}

	@Test
	void invalidValuesFailWithTheValueObjectExceptionAsCause() {
		assertThatThrownBy(() -> jsonMapper.readValue("{\"price\":10.005}", Priced.class))
			.isInstanceOf(JacksonException.class)
			.hasRootCauseInstanceOf(InvalidValueException.class);
		assertThatThrownBy(() -> jsonMapper.readValue("{\"price\":-1}", Priced.class))
			.hasRootCauseInstanceOf(InvalidValueException.class);
		assertThatThrownBy(() -> jsonMapper.readValue("{\"phone\":\"01712345678\"}", Priced.class))
			.hasRootCauseInstanceOf(InvalidValueException.class);
	}

}