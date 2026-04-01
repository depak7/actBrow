package com.actbrow.actbrow.agent;

import java.util.Map;

public record ToolCall(
	String toolCallId,
	String toolId,
	String toolKey,
	Map<String, Object> arguments
) {
}
