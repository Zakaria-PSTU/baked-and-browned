package com.bakedandbrowned.identity.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bakedandbrowned.common.ratelimit.RateLimiter;
import com.bakedandbrowned.common.security.CsrfGuard;
import com.bakedandbrowned.common.web.ApiException;
import com.bakedandbrowned.identity.dto.AuthResponse;
import com.bakedandbrowned.identity.dto.LoginRequest;
import com.bakedandbrowned.identity.dto.RegisterRequest;
import com.bakedandbrowned.identity.dto.UserResponse;
import com.bakedandbrowned.identity.service.AuthService;
import com.bakedandbrowned.identity.service.AuthSession;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

/** Contract tag {@code Auth}: {@code /api/v1/auth/*}. */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

	private final AuthService auth;

	private final AuthCookies cookies;

	private final CsrfGuard csrf;

	private final RateLimiter rateLimiter;

	AuthController(AuthService auth, AuthCookies cookies, CsrfGuard csrf, RateLimiter rateLimiter) {
		this.auth = auth;
		this.cookies = cookies;
		this.csrf = csrf;
		this.rateLimiter = rateLimiter;
	}

	@PostMapping("/register")
	ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest body, HttpServletRequest request) {
		rateLimiter.check("register", request.getRemoteAddr());
		return ResponseEntity.status(HttpStatus.CREATED).body(auth.register(body));
	}

	@PostMapping("/login")
	AuthResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request,
			HttpServletResponse response) {
		rateLimiter.check("login", request.getRemoteAddr());
		return startSession(auth.login(body), response);
	}

	@PostMapping("/refresh")
	AuthResponse refresh(HttpServletRequest request, HttpServletResponse response) {
		rateLimiter.check("refresh", request.getRemoteAddr());
		csrf.verify(request);
		try {
			return startSession(auth.refresh(cookies.refreshToken(request)), response);
		}
		catch (ApiException ex) {
			// The session is gone; stop the browser from presenting the dead cookie again.
			cookies.clear(response);
			throw ex;
		}
	}

	@PostMapping("/logout")
	ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
		csrf.verify(request);
		auth.logout(cookies.refreshToken(request));
		cookies.clear(response);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/me")
	UserResponse me(@AuthenticationPrincipal Jwt jwt) {
		return auth.currentUser(UUID.fromString(jwt.getSubject()));
	}

	private AuthResponse startSession(AuthSession session, HttpServletResponse response) {
		cookies.setSession(response, session.refreshToken());
		return session.response();
	}

}
