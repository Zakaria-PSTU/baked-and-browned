package com.bakedandbrowned.identity.dto;

import org.jspecify.annotations.Nullable;

import com.bakedandbrowned.common.value.PhoneNumber;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Contract {@code RegisterRequest}. {@code phone} is validated by {@link PhoneNumber} (422 when malformed). */
public record RegisterRequest(@NotBlank @Size(min = 2, max = 150) String fullName, @NotNull PhoneNumber phone,
		@Email @Size(max = 255) @Nullable String email, @NotNull @Size(min = 12, max = 128) String password) {

	/** Never print the password. */
	@Override
	public String toString() {
		return "RegisterRequest[phone=" + phone + "]";
	}

}
