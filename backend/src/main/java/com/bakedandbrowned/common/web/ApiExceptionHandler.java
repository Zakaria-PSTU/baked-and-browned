package com.bakedandbrowned.common.web;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.bakedandbrowned.common.value.InvalidValueException;

import tools.jackson.core.JacksonException;

/**
 * Renders every MVC error as {@code application/problem+json} per the contract's {@code Problem}
 * schema: {@code title}, {@code status}, {@code detail}, plus a machine-readable {@code code} and
 * the request's {@code traceId}.
 * <ul>
 * <li>Bean Validation failures and invalid value objects ({@link InvalidValueException}) → 422
 * {@code VALIDATION_FAILED}, naming the fields but never echoing rejected values.</li>
 * <li>Unreadable bodies (malformed JSON, wrong JSON types) → 400 {@code MALFORMED_REQUEST}.</li>
 * <li>{@link ApiException} → its own status and code.</li>
 * <li>Anything unexpected → 500 {@code INTERNAL_ERROR} with a generic detail; the cause is only
 * logged, with the trace id.</li>
 * </ul>
 * Other framework errors (404, 405, 415, ...) keep Spring's status and detail and get the
 * HTTP status name as code (for example {@code NOT_FOUND}).
 * <p>
 * Controllers must not be annotated {@code @Validated}: that swaps Spring MVC's built-in method
 * validation (422 here) for an AOP proxy whose {@code ConstraintViolationException} is a 500.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	public static final String VALIDATION_FAILED = "VALIDATION_FAILED";

	public static final String MALFORMED_REQUEST = "MALFORMED_REQUEST";

	public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(ApiException.class)
	ResponseEntity<Object> handleApiException(ApiException ex, WebRequest request) {
		HttpHeaders headers = new HttpHeaders();
		if (ex instanceof RateLimitExceededException limited) {
			headers.set(HttpHeaders.RETRY_AFTER, Long.toString(limited.retryAfterSeconds()));
		}
		return handleExceptionInternal(ex, problem(ex.status(), ex.code(), ex.getMessage()), headers,
				ex.status(), request);
	}

	/**
	 * Method-security denials ({@code @PreAuthorize}) are thrown inside MVC, so they would otherwise
	 * reach the catch-all below as a 500. URL rules already require authentication for every
	 * non-public path, so the caller here is authenticated: 403.
	 */
	@ExceptionHandler(AccessDeniedException.class)
	ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex, WebRequest request) {
		HttpStatus status = HttpStatus.FORBIDDEN;
		return handleExceptionInternal(ex,
				problem(status, "FORBIDDEN", "You do not have permission to perform this action."), new HttpHeaders(),
				status, request);
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
		log.error("Unhandled exception", ex);
		HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
		return handleExceptionInternal(ex, problem(status, INTERNAL_ERROR, "An unexpected error occurred."),
				new HttpHeaders(), status, request);
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<String> errors = new ArrayList<>();
		ex.getBindingResult().getFieldErrors().forEach(e -> errors.add(e.getField() + ": " + e.getDefaultMessage()));
		ex.getBindingResult().getGlobalErrors().forEach(e -> errors.add(globalError(e)));
		return validationFailed(ex, errors, headers, request);
	}

	@Override
	protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<String> errors = new ArrayList<>();
		ex.getParameterValidationResults().forEach(result -> {
			String name = result.getMethodParameter().getParameterName();
			for (MessageSourceResolvable error : result.getResolvableErrors()) {
				errors.add(name + ": " + error.getDefaultMessage());
			}
		});
		return validationFailed(ex, errors, headers, request);
	}

	@Override
	protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		InvalidValueException invalid = findCause(ex, InvalidValueException.class);
		if (invalid != null) {
			JacksonException json = findCause(ex, JacksonException.class);
			String path = json == null ? "" : jsonPath(json);
			return validationFailed(ex, List.of(path.isEmpty() ? invalid.getMessage() : path + ": " + invalid.getMessage()),
					headers, request);
		}
		HttpStatus badRequest = HttpStatus.BAD_REQUEST;
		return handleExceptionInternal(ex,
				problem(badRequest, MALFORMED_REQUEST, "Request body is missing or is not valid JSON for this operation."),
				headers, badRequest, request);
	}

	/** Every problem body passes through here: add the default code and the trace id. */
	@Override
	protected ResponseEntity<Object> createResponseEntity(@Nullable Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest request) {
		if (body instanceof ProblemDetail problem) {
			if (problem.getProperties() == null || !problem.getProperties().containsKey("code")) {
				HttpStatus resolved = HttpStatus.resolve(statusCode.value());
				problem.setProperty("code", resolved == null ? "ERROR" : resolved.name());
			}
			String traceId = request instanceof ServletWebRequest servlet ? TraceIdFilter.traceId(servlet.getRequest())
					: null;
			if (traceId != null) {
				problem.setProperty("traceId", traceId);
			}
		}
		return super.createResponseEntity(body, headers, statusCode, request);
	}

	private ResponseEntity<Object> validationFailed(Exception ex, List<String> errors, HttpHeaders headers,
			WebRequest request) {
		HttpStatus status = HttpStatus.UNPROCESSABLE_CONTENT;
		String detail = errors.isEmpty() ? "Request validation failed." : String.join("; ", errors.stream().sorted().toList());
		return handleExceptionInternal(ex, problem(status, VALIDATION_FAILED, detail), headers, status, request);
	}

	private static ProblemDetail problem(HttpStatus status, String code, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setProperty("code", code);
		return problem;
	}

	private static String globalError(ObjectError error) {
		return error.getObjectName() + ": " + error.getDefaultMessage();
	}

	/** JSON location of the failing value, for example {@code items[0].unitPrice}. */
	private static String jsonPath(JacksonException ex) {
		StringBuilder path = new StringBuilder();
		for (JacksonException.Reference ref : ex.getPath()) {
			if (ref.getPropertyName() != null) {
				if (!path.isEmpty()) {
					path.append('.');
				}
				path.append(ref.getPropertyName());
			}
			else if (ref.getIndex() >= 0) {
				path.append('[').append(ref.getIndex()).append(']');
			}
		}
		return path.toString();
	}

	private static <T extends Throwable> @Nullable T findCause(Throwable ex, Class<T> type) {
		for (Throwable t = ex; t != null; t = t.getCause()) {
			if (type.isInstance(t)) {
				return type.cast(t);
			}
		}
		return null;
	}

}