package com.bakedandbrowned.common.security;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import com.bakedandbrowned.common.web.ApiException;

import jakarta.servlet.http.Cookie;

class CsrfGuardTest {

	private static final SecurityProperties.Sessions SESSIONS = new SecurityProperties.Sessions(
			new SecurityProperties.SessionLifetime(Duration.ofDays(14), Duration.ofDays(30)),
			new SecurityProperties.SessionLifetime(Duration.ofHours(8), Duration.ofHours(12)));

	private final CsrfGuard guard = new CsrfGuard(new SecurityProperties(
			new SecurityProperties.Jwt("iss", "aud", Duration.ofMinutes(15), "kid", null, true), SESSIONS, true,
			List.of("https://bakedandbrowned.com.bd")));

	@Test
	void acceptsAllowedOriginWithMatchingTokens() {
		assertThatNoException().isThrownBy(() -> guard.verify(request("https://bakedandbrowned.com.bd", "abc", "abc")));
	}

	@Test
	void rejectsEveryMissingOrMismatchedPart() {
		for (MockHttpServletRequest request : List.of(request(null, "abc", "abc"),
				request("https://bakedandbrowned.com.bd.evil.example", "abc", "abc"),
				request("http://bakedandbrowned.com.bd", "abc", "abc"), request("https://bakedandbrowned.com.bd", null, "abc"),
				request("https://bakedandbrowned.com.bd", "abc", null), request("https://bakedandbrowned.com.bd", "", ""),
				request("https://bakedandbrowned.com.bd", "abc", "abd"))) {
			assertThatThrownBy(() -> guard.verify(request)).isInstanceOfSatisfying(ApiException.class, ex -> {
				org.assertj.core.api.Assertions.assertThat(ex.status()).isEqualTo(HttpStatus.FORBIDDEN);
				org.assertj.core.api.Assertions.assertThat(ex.code()).isEqualTo("CSRF_REJECTED");
			});
		}
	}

	private static MockHttpServletRequest request(String origin, String header, String cookie) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/refresh");
		if (origin != null) {
			request.addHeader("Origin", origin);
		}
		if (header != null) {
			request.addHeader(CsrfGuard.HEADER, header);
		}
		if (cookie != null) {
			request.setCookies(new Cookie(CsrfGuard.COOKIE, cookie));
		}
		return request;
	}

}
