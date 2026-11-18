package com.bakedandbrowned.common.value;

import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A normalized Bangladeshi mobile number, {@code +8801[3-9]XXXXXXXX}: the same pattern as the
 * API contract and the database {@code ck_phone} constraint. Other spellings (for example
 * {@code 01712345678}) are rejected rather than rewritten, as the contract requires (422).
 * <p>
 * {@link #toString()} is masked so a number logged by accident is not exposed;
 * use {@link #value()} where the full number is needed.
 */
public final class PhoneNumber {

	private static final Pattern FORMAT = Pattern.compile("^\\+8801[3-9][0-9]{8}$");

	private final String value;

	private PhoneNumber(String value) {
		this.value = value;
	}

	@JsonCreator(mode = JsonCreator.Mode.DELEGATING)
	public static PhoneNumber of(String value) {
		if (value == null || !FORMAT.matcher(value).matches()) {
			throw new InvalidValueException("phone must be a Bangladeshi mobile number in the form +8801XXXXXXXXX");
		}
		return new PhoneNumber(value);
	}

	@JsonValue
	public String value() {
		return value;
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof PhoneNumber other && value.equals(other.value);
	}

	@Override
	public int hashCode() {
		return value.hashCode();
	}

	/** Masked, for example {@code +8801*******89}. */
	@Override
	public String toString() {
		return value.substring(0, 5) + "*".repeat(value.length() - 7) + value.substring(value.length() - 2);
	}

}