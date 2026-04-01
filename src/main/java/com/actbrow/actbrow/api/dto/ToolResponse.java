package com.actbrow.actbrow.api.dto;

import java.time.Instant;
import java.util.Map;

import com.actbrow.actbrow.model.ToolType;

public record ToolResponse(
	String id,
	String key,
	String displayName,
	String description,
	Map<String, Object> inputSchema,
	Map<String, Object> outputSchema,
	ToolType type,
	String version,
	boolean enabled,
	String executorRef,
	Map<String, Object> defaultArguments,
	Instant createdAt
) {
}
