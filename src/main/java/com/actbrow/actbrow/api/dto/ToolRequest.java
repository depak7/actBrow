package com.actbrow.actbrow.api.dto;

import java.util.Map;

import com.actbrow.actbrow.model.ToolType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ToolRequest(
	@NotBlank String key,
	@NotBlank String displayName,
	@NotBlank String description,
	@NotNull Map<String, Object> inputSchema,
	Map<String, Object> outputSchema,
	@NotNull ToolType type,
	@NotBlank String version,
	boolean enabled,
	String executorRef,
	Map<String, Object> defaultArguments
) {
}
