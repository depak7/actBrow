package com.actbrow.actbrow.api.dto;

import java.time.Instant;

public record AssistantResponse(
	String id,
	String key,
	String name,
	String systemPrompt,
	String model,
	Instant createdAt
) {
}
