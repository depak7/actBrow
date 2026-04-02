package com.actbrow.actbrow.agent;

import java.util.Map;

import com.actbrow.actbrow.model.ToolType;

public record ToolDescriptor(
	String id,
	String key,
	String description,
	String inputSchema,
	ToolType type,
	String executorRef,
	Map<String, Object> defaultArguments,
	Map<String, Object> metadata
) {
}
