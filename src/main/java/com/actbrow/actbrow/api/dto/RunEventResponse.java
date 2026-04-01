package com.actbrow.actbrow.api.dto;

import java.time.Instant;
import java.util.Map;

public record RunEventResponse(
	String runId,
	String eventType,
	Instant createdAt,
	Map<String, Object> payload
) {
}
