package com.bakedandbrowned.identity.controller;

import java.time.Duration;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import com.bakedandbrowned.common.security.CsrfGuard;
import com.bakedandbrowned.common.security.SecurityProperties;
import com.bakedandbrowned.identity.service.OpaqueTokens;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Auth cookies (architecture, Identity and security):
 * <ul>
 * <li>{@value #REFRESH_COOKIE}: the opaque refresh token; {@code HttpOnly}, {@code Secure},
 * {@code SameSite=Strict}, sent only to {@value #REFRESH_PATH}.</li>
 * <li>{@code XSRF-TOKEN}: a fresh random CSRF token, readable by the SPA (not {@code HttpOnly}),
 * which echoes it in {@code X-CSRF-Token}; rotated with every session.</li>
 * </ul>
 */
@Component
class AuthCookies {

	static final String REFRESH_COOKIE = "refresh_token";

	static final String REFRESH_PATH = "/api/v1/auth";

	private final boolean secure;

	private final Duration maxAge;

	AuthCookies(SecurityProperties properties) {
		this.secure = properties.secureCookies();
		// Same lifetime as the refresh token row (AuthService), so the cookie never outlives it by much.
		this.maxAge = properties.refreshTokenTtl();
	}

	@Nullable String refreshToken(HttpServletRequest request) {
		return CsrfGuard.cookie(request, REFRESH_COOKIE);
	}

	void setSession(HttpServletResponse response, String refreshToken) {
		response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie(refreshToken, maxAge).toString());
		response.addHeader(HttpHeaders.SET_COOKIE, csrfCookie(OpaqueTokens.generate(), maxAge).toString());
	}

	void clear(HttpServletResponse response) {
		response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie("", Duration.ZERO).toString());
		response.addHeader(HttpHeaders.SET_COOKIE, csrfCookie("", Duration.ZERO).toString());
	}

	private ResponseCookie refreshCookie(String value, Duration maxAge) {
		return ResponseCookie.from(REFRESH_COOKIE, value)
			.httpOnly(true)
			.secure(secure)
			.sameSite("Strict")
			.path(REFRESH_PATH)
			.maxAge(maxAge)
			.build();
	}

	private ResponseCookie csrfCookie(String value, Duration maxAge) {
		return ResponseCookie.from(CsrfGuard.COOKIE, value)
			.httpOnly(false)
			.secure(secure)
			.sameSite("Strict")
			.path("/")
			.maxAge(maxAge)
			.build();
	}

}
