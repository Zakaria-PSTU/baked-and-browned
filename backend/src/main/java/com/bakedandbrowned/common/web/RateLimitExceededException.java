package com.bakedandbrowned.common.web;

import org.springframework.http.HttpStatus;

/** 429 {@code RATE_LIMITED}, rendered with a {@code Retry-After} header (whole seconds, at least 1). */
public class RateLimitExceededException extends ApiException {

	private static final long serialVersionUID = 1L;

	public static final String RATE_LIMITED = "RATE_LIMITED";

	private final long retryAfterSeconds;

	public RateLimitExceededException(long retryAfterSeconds) {
		super(HttpStatus.TOO_MANY_REQUESTS, RATE_LIMITED, "Too many requests. Try again later.");
		this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
	}

	public long retryAfterSeconds() {
		return retryAfterSeconds;
	}

}
