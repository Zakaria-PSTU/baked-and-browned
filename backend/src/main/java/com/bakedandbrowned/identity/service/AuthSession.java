package com.bakedandbrowned.identity.service;

import java.time.Duration;

import com.bakedandbrowned.identity.dto.AuthResponse;

/**
 * Result of login or refresh: the response body plus the raw refresh token for the HttpOnly cookie
 * (the only place it ever appears; the database keeps its hash), and how long that token is valid,
 * which is the cookies' {@code Max-Age}.
 */
public record AuthSession(AuthResponse response, String refreshToken, Duration refreshTokenLifetime) {

	/** Never print the token. */
	@Override
	public String toString() {
		return "AuthSession[" + response + "]";
	}

}
