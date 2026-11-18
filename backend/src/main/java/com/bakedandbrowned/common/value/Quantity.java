package com.bakedandbrowned.common.value;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A non-negative ingredient quantity with exactly three decimal places, fitting
 * {@code NUMERIC(14,3)}, in the ingredient's own unit (contract {@code Quantity}).
 * <p>
 * JSON: written with three decimals ({@code 2.500}); read rejecting negative values and more than
 * three significant decimals ({@link InvalidValueException}, mapped to 422). Recipe arithmetic only
 * multiplies by integer item quantities and adds, so no rounding ever occurs.
 */
public final class Quantity implements Comparable<Quantity> {

	public static final int SCALE = 3;

	/** {@code NUMERIC(14,3)}: at most 11 integer digits. */
	private static final int MAX_PRECISION = 14;

	public static final Quantity ZERO = new Quantity(BigDecimal.ZERO.setScale(SCALE));

	private final BigDecimal value;

	private Quantity(BigDecimal value) {
		this.value = value;
	}

	@JsonCreator(mode = JsonCreator.Mode.DELEGATING)
	public static Quantity of(BigDecimal value) {
		if (value == null) {
			throw new InvalidValueException("quantity is required");
		}
		if (value.signum() < 0) {
			throw new InvalidValueException("quantity must not be negative");
		}
		if (value.stripTrailingZeros().scale() > SCALE) {
			throw new InvalidValueException("quantity must have at most 3 decimal places");
		}
		return checked(value.setScale(SCALE, RoundingMode.UNNECESSARY));
	}

	public static Quantity of(String value) {
		return of(new BigDecimal(value));
	}

	public Quantity plus(Quantity other) {
		return checked(value.add(other.value));
	}

	/** Server-side arithmetic only: a negative result is a bookkeeping bug (500), not client input. */
	public Quantity minus(Quantity other) {
		BigDecimal result = value.subtract(other.value);
		if (result.signum() < 0) {
			throw new IllegalArgumentException("quantity subtraction would be negative");
		}
		return new Quantity(result);
	}

	public Quantity times(int count) {
		if (count < 0) {
			throw new IllegalArgumentException("count must not be negative");
		}
		return checked(value.multiply(BigDecimal.valueOf(count)));
	}

	public boolean isZero() {
		return value.signum() == 0;
	}

	public boolean isPositive() {
		return value.signum() > 0;
	}

	/** The value, always with scale 3. */
	@JsonValue
	public BigDecimal value() {
		return value;
	}

	private static Quantity checked(BigDecimal value) {
		if (value.precision() > MAX_PRECISION) {
			throw new InvalidValueException("quantity exceeds the maximum supported value");
		}
		return new Quantity(value);
	}

	@Override
	public int compareTo(Quantity other) {
		return value.compareTo(other.value);
	}

	/** Scale is always 3, so {@link BigDecimal#equals} is value equality here. */
	@Override
	public boolean equals(Object o) {
		return o instanceof Quantity other && value.equals(other.value);
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(value);
	}

	@Override
	public String toString() {
		return value.toPlainString();
	}

}
