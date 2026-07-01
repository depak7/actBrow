package com.actbrow.actbrow.api;

/**
 * Thrown when a requested resource does not exist. Mapped to HTTP 404 by {@link ApiExceptionHandler},
 * distinct from validation failures ({@link IllegalArgumentException}) which map to 400.
 */
public class NotFoundException extends RuntimeException {

	public NotFoundException(String message) {
		super(message);
	}
}
