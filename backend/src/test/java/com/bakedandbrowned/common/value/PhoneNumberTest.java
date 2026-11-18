package com.bakedandbrowned.common.value;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PhoneNumberTest {

	@ParameterizedTest
	@ValueSource(strings = { "+8801312345678", "+8801712345678", "+8801999999999" })
	void acceptsNormalizedBangladeshiMobileNumbers(String input) {
		assertThat(PhoneNumber.of(input).value()).isEqualTo(input);
	}

	@ParameterizedTest
	@ValueSource(strings = { "01712345678", "8801712345678", "+880171234567", "+88017123456789", "+8801212345678",
			"+8802712345678", "+880 1712345678", "+8801712345678\n", "" })
	void rejectsAnythingElseWithoutEchoingTheValue(String input) {
		assertThatThrownBy(() -> PhoneNumber.of(input)).isInstanceOf(InvalidValueException.class)
			.hasMessage("phone must be a Bangladeshi mobile number in the form +8801XXXXXXXXX");
	}

	@Test
	void rejectsNull() {
		assertThatThrownBy(() -> PhoneNumber.of(null)).isInstanceOf(InvalidValueException.class);
	}

	@Test
	void equalityIsByValue() {
		assertThat(PhoneNumber.of("+8801712345678")).isEqualTo(PhoneNumber.of("+8801712345678"))
			.hasSameHashCodeAs(PhoneNumber.of("+8801712345678"))
			.isNotEqualTo(PhoneNumber.of("+8801712345679"));
	}

	@Test
	void toStringIsMaskedSoAccidentalLoggingDoesNotLeakTheNumber() {
		assertThat(PhoneNumber.of("+8801712345689")).hasToString("+8801*******89");
	}

}