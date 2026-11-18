package com.bakedandbrowned.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Contract {@code LoginRequest}: {@code identifier} is a phone ({@code +8801XXXXXXXXX}) or an email.
 * The password has no length constraint here so a wrong length is reported as invalid credentials
 * (401), like any other wrong password.
 */
public record LoginRequest(@NotBlank @Size(max = 255) String identifier, @NotNull String password) {

	/** Never print the identifier or password. */
	@Override
	public String toString() {
		return "LoginRequest[]";
	}

}
