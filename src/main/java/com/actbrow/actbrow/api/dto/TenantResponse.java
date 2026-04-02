package com.actbrow.actbrow.api.dto;

import java.time.Instant;

public record TenantResponse(
	String id,
	String key,
	String name,
	String apiKey,
	boolean enabled,
	Instant createdAt
) {
}
