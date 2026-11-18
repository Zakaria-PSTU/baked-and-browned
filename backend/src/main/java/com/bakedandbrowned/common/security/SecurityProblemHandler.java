package com.bakedandbrowned.common.security;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import com.bakedandbrowned.common.web.TraceIdFilter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * Renders security-filter failures (which happen before Spring MVC, so outside
 * {@code ApiExceptionHandler}) as the same problem+json shape: 401 {@code UNAUTHENTICATED} or
 * {@code INVALID_TOKEN} with an RFC 6750 {@code WWW-Authenticate: Bearer} challenge, and 403
 * {@code FORBIDDEN}. Details are generic: token parsing errors are never echoed.
 */
@Component
public class SecurityProblemHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

	public static final String UNAUTHENTICATED = "UNAUTHENTICATED";

	public static final String INVALID_TOKEN = "INVALID_TOKEN";

	public static final String FORBIDDEN = "FORBIDDEN";

	private final JsonMapper jsonMapper;

	public SecurityProblemHandler(JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
			throws IOException {
		boolean invalidToken = ex instanceof InvalidBearerTokenException;
		// RFC 6750 challenge. Written directly: Spring's entry point would also advertise an RFC 9728
		// resource-metadata URL that this API does not serve.
		response.setHeader(HttpHeaders.WWW_AUTHENTICATE, invalidToken ? "Bearer error=\"invalid_token\"" : "Bearer");
		write(request, response, HttpStatus.UNAUTHORIZED, invalidToken ? INVALID_TOKEN : UNAUTHENTICATED,
				invalidToken ? "The access token is invalid or has expired." : "Authentication is required.");
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
			throws IOException {
		write(request, response, HttpStatus.FORBIDDEN, FORBIDDEN, "You do not have permission to perform this action.");
	}

	private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String code,
			String detail) throws IOException {
		Map<String, Object> problem = new LinkedHashMap<>();
		problem.put("type", "about:blank");
		problem.put("title", status.getReasonPhrase());
		problem.put("status", status.value());
		problem.put("detail", detail);
		problem.put("instance", request.getRequestURI());
		problem.put("code", code);
		String traceId = TraceIdFilter.traceId(request);
		if (traceId != null) {
			problem.put("traceId", traceId);
		}
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		jsonMapper.writeValue(response.getOutputStream(), problem);
	}

}
