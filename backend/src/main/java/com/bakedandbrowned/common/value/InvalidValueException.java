package com.bakedandbrowned.common.value;

/**
 * A client-supplied value is not acceptable (for example a money amount with three decimals or a
 * phone number that is not a normalized Bangladeshi mobile number). The web layer maps it to 422.
 * <p>
 * Messages are fixed, safe texts: they never echo the rejected value, which may be personal data.
 */
public class InvalidValueException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public InvalidValueException(String message) {
		super(message);
	}

}