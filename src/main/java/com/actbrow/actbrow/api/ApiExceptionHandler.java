package com.actbrow.actbrow.api;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

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

	private static String messageOrDefault(Exception exception, String fallback) {
		return exception.getMessage() != null ? exception.getMessage() : fallback;
	}
}
