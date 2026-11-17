package com.bakedandbrowned.common.web;

import org.springframework.http.HttpStatus;

/**
 * An expected, client-visible failure: rendered as problem+json with this status, a stable
 * machine-readable {@code code} (for example {@code IDEMPOTENCY_KEY_REUSED}) and a safe detail.
 * The detail is shown to the caller, so it must not contain internal or personal data.
 */
public class ApiException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final HttpStatus status;

	private final String code;

	public ApiException(HttpStatus status, String code, String detail) {
		super(detail);
		this.status = status;
		this.code = code;
	}

	public static ApiException notFound(String code, String detail) {
		return new ApiException(HttpStatus.NOT_FOUND, code, detail);
	}

	/** Capacity, inventory or state conflict (409). */
	public static ApiException conflict(String code, String detail) {
		return new ApiException(HttpStatus.CONFLICT, code, detail);
	}

	/** Invalid business input (422). */
	public static ApiException unprocessable(String code, String detail) {
		return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, code, detail);
	}

	public HttpStatus status() {
		return status;
	}

	public String code() {
		return code;
	}

}