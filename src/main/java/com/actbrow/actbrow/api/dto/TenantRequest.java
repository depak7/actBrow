package com.actbrow.actbrow.api.dto;

import jakarta.validation.constraints.NotBlank;

public record TenantRequest(
	@NotBlank String key,
	@NotBlank String name,
	String apiKey,
	boolean enabled,
	String userId
) {
}
