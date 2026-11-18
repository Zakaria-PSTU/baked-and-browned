package com.bakedandbrowned.identity.service;

import com.bakedandbrowned.identity.dto.AuthResponse;

/**
 * Result of login or refresh: the response body plus the raw refresh token for the HttpOnly cookie
 * (the only place it ever appears; the database keeps its hash).
 */
public record AuthSession(AuthResponse response, String refreshToken) {

	/** Never print the token. */
	@Override
	public String toString() {
		return "AuthSession[" + response + "]";
	}

}
