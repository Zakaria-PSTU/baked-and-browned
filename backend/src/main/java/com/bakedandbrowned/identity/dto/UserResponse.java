package com.bakedandbrowned.identity.dto;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.bakedandbrowned.common.value.PhoneNumber;
import com.bakedandbrowned.identity.entity.Role;
import com.bakedandbrowned.identity.entity.UserAccount;

/** Contract {@code User}. */
public record UserResponse(UUID id, String fullName, PhoneNumber phone, @Nullable String email, Role role) {

	public static UserResponse from(UserAccount user) {
		return new UserResponse(user.getId(), user.getFullName(), user.getPhone(), user.getEmail(), user.getRole());
	}

}
