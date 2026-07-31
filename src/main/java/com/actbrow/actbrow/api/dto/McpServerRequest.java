package com.actbrow.actbrow.api.dto;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;

public record McpServerRequest(
	@NotBlank String name,
	@NotBlank String serverUrl,
	Map<String, String> authHeaders,
	Boolean enabled
) {
}
