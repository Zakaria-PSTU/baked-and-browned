package com.bakedandbrowned.identity.dto;

/** Contract {@code AuthResponse}; {@code expiresIn} is the access token's lifetime in seconds. */
public record AuthResponse(String accessToken, String tokenType, long expiresIn, UserResponse user) {

	public static AuthResponse bearer(String accessToken, long expiresIn, UserResponse user) {
		return new AuthResponse(accessToken, "Bearer", expiresIn, user);
	}

	/** Never print the token. */
	@Override
	public String toString() {
		return "AuthResponse[user=" + user.id() + "]";
	}

}
