package com.bakedandbrowned.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.bakedandbrowned.common.web.ApiException;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

/**
 * CSRF defense for the cookie-authenticated endpoints ({@code /auth/refresh}, {@code /auth/logout}):
 * <ol>
 * <li>the {@code Origin} header must be present and exactly one of
 * {@code app.security.allowed-origins};</li>
 * <li>double submit: the {@value #HEADER} header must equal the readable {@value #COOKIE} cookie
 * (constant-time comparison).</li>
 * </ol>
 * Any failure is 403 {@code CSRF_REJECTED}, with the same detail so the response does not say which
 * check failed.
 */
@Component
public class CsrfGuard {

	public static final String COOKIE = "XSRF-TOKEN";

	public static final String HEADER = "X-CSRF-Token";

	public static final String CSRF_REJECTED = "CSRF_REJECTED";

	private final List<String> allowedOrigins;

	public CsrfGuard(SecurityProperties properties) {
		this.allowedOrigins = properties.allowedOrigins();
	}

	public void verify(HttpServletRequest request) {
		String origin = request.getHeader("Origin");
		String header = request.getHeader(HEADER);
		String cookie = cookie(request, COOKIE);
		boolean originAllowed = origin != null && allowedOrigins.contains(origin);
		boolean tokenMatches = header != null && cookie != null && !cookie.isEmpty()
				&& MessageDigest.isEqual(header.getBytes(StandardCharsets.UTF_8), cookie.getBytes(StandardCharsets.UTF_8));
		if (!originAllowed || !tokenMatches) {
			throw ApiException.forbidden(CSRF_REJECTED, "The request failed cross-site request forgery checks.");
		}
	}

	/** The first cookie with this name, or {@code null}. */
	public static @Nullable String cookie(HttpServletRequest request, String name) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return null;
		}
		return Arrays.stream(cookies).filter(c -> name.equals(c.getName())).map(Cookie::getValue).findFirst().orElse(null);
	}

}
