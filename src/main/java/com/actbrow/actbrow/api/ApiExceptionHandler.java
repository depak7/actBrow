package com.actbrow.actbrow.api;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;

/**
 * Central translation of exceptions into API responses. The guiding rule: internal failure detail
 * (stack traces, SQL, driver messages) must never reach the client. Only intentional, user-facing
 * validation messages ({@link IllegalArgumentException}, {@link NotFoundException}) are passed through;
 * everything else is logged server-side and returned as a generic message.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(IllegalArgumentException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public Map<String, String> handleIllegalArgument(IllegalArgumentException exception) {
		return Map.of("error", messageOrDefault(exception, "Bad request"));
	}

	@ExceptionHandler(NotFoundException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public Map<String, String> handleNotFound(NotFoundException exception) {
		return Map.of("error", messageOrDefault(exception, "Not found"));
	}

	/**
	 * Malformed / invalid request bodies and parameters (e.g. bean-validation failures). The concrete
	 * reason can echo internal binding detail, so we return a fixed generic message.
	 */
	@ExceptionHandler(ServerWebInputException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public Map<String, String> handleInvalidInput(ServerWebInputException exception) {
		log.debug("Rejected invalid request input", exception);
		return Map.of("error", "The request was invalid or malformed.");
	}

	/**
	 * Any explicitly-raised HTTP status exception: preserve the status code but never leak its reason,
	 * which may contain internal detail.
	 */
	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException exception) {
		HttpStatusCode status = exception.getStatusCode();
		if (status.is5xxServerError()) {
			log.error("Server error handling request", exception);
		}
		else {
			log.debug("Request rejected with status {}", status, exception);
		}
		return ResponseEntity.status(status).body(Map.of("error", genericMessageForStatus(status)));
	}

	/**
	 * Catch-all: any unhandled exception is an internal failure. Log the full detail server-side and
	 * return an opaque 500 so no stack trace, SQL, or exception message escapes to the client.
	 */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<Map<String, String>> handleUnexpected(Exception exception) {
		log.error("Unhandled exception while processing request", exception);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
			.body(Map.of("error", "Something went wrong on our end. Please try again."));
	}

	private static String genericMessageForStatus(HttpStatusCode status) {
		if (status.value() == HttpStatus.UNAUTHORIZED.value()) {
			return "Authentication is required.";
		}
		if (status.value() == HttpStatus.FORBIDDEN.value()) {
			return "You do not have access to this resource.";
		}
		if (status.value() == HttpStatus.NOT_FOUND.value()) {
			return "Not found";
		}
		if (status.is4xxClientError()) {
			return "The request could not be processed.";
		}
		return "Something went wrong on our end. Please try again.";
	}

	private static String messageOrDefault(Exception exception, String fallback) {
		return exception.getMessage() != null ? exception.getMessage() : fallback;
	}
}
