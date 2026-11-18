package com.bakedandbrowned.common.value;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A non-negative BDT amount with exactly two decimal places, fitting {@code NUMERIC(12,2)}.
 * <p>
 * JSON: written as a number with exactly two decimals ({@code 1250.00}); read from a number,
 * rejecting negative amounts and amounts with more than two significant decimals
 * ({@link InvalidValueException}, mapped to 422). Jackson alone neither pads nor rejects scale
 * (see {@code MoneyJsonSerializationSpikeTest}), so this type does both.
 * <p>
 * MVP pricing only adds and multiplies by integer quantities, so no rounding ever occurs
 * (business-rules.md). A rule that divides must round HALF_UP explicitly and be documented.
 */
public final class Money implements Comparable<Money> {

	public static final int SCALE = 2;

	/** {@code NUMERIC(12,2)}: at most 10 integer digits. */
	private static final int MAX_PRECISION = 12;

	public static final Money ZERO = new Money(BigDecimal.ZERO.setScale(SCALE));

	private final BigDecimal amount;

	private Money(BigDecimal amount) {
		this.amount = amount;
	}

	/**
	 * Validates a client- or database-supplied amount. Trailing zeros beyond two decimals are
	 * accepted ({@code 10.500}), real extra precision is not ({@code 10.005}).
	 */
	@JsonCreator(mode = JsonCreator.Mode.DELEGATING)
	public static Money of(BigDecimal amount) {
		if (amount == null) {
			throw new InvalidValueException("amount is required");
		}
		if (amount.signum() < 0) {
			throw new InvalidValueException("amount must not be negative");
		}
		if (amount.stripTrailingZeros().scale() > SCALE) {
			throw new InvalidValueException("amount must have at most 2 decimal places");
		}
		return checked(amount.setScale(SCALE, RoundingMode.UNNECESSARY));
	}

	public static Money of(String amount) {
		return of(new BigDecimal(amount));
	}

	public Money plus(Money other) {
		return checked(amount.add(other.amount));
	}

	/**
	 * Server-side arithmetic only: a negative result is a pricing bug, not bad client input,
	 * so it fails with {@link IllegalArgumentException} (500), not 422.
	 */
	public Money minus(Money other) {
		BigDecimal result = amount.subtract(other.amount);
		if (result.signum() < 0) {
			throw new IllegalArgumentException("money subtraction would be negative");
		}
		return new Money(result);
	}

	public Money times(int quantity) {
		if (quantity < 0) {
			throw new IllegalArgumentException("quantity must not be negative");
		}
		return checked(amount.multiply(BigDecimal.valueOf(quantity)));
	}

	public boolean isZero() {
		return amount.signum() == 0;
	}

	/** The amount, always with scale 2. */
	@JsonValue
	public BigDecimal amount() {
		return amount;
	}

	private static Money checked(BigDecimal amount) {
		if (amount.precision() > MAX_PRECISION) {
			throw new InvalidValueException("amount exceeds the maximum supported value");
		}
		return new Money(amount);
	}

	@Override
	public int compareTo(Money other) {
		return amount.compareTo(other.amount);
	}

	/** Scale is always 2, so {@link BigDecimal#equals} is value equality here. */
	@Override
	public boolean equals(Object o) {
		return o instanceof Money other && amount.equals(other.amount);
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(amount);
	}

	@Override
	public String toString() {
		return amount.toPlainString();
	}

	/**
	 * For customer-facing messages: {@code BDT 1,25,000.00} (South Asian digit grouping, as the web
	 * client shows amounts). Never parse this back; use {@link #amount()} for values.
	 */
	public String toDisplayString() {
		String plain = amount.toPlainString();
		int dot = plain.indexOf('.');
		String whole = plain.substring(0, dot);
		StringBuilder grouped = new StringBuilder();
		int length = whole.length();
		for (int i = 0; i < length; i++) {
			int fromEnd = length - i;
			if (i > 0 && (fromEnd == 3 || (fromEnd > 3 && (fromEnd - 3) % 2 == 0))) {
				grouped.append(',');
			}
			grouped.append(whole.charAt(i));
		}
		return "BDT " + grouped + plain.substring(dot);
	}

}