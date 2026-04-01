package com.actbrow.actbrow.agent;

public record ToolExecutionResult(
	boolean success,
	String structuredOutput,
	String textSummary,
	String error
) {
}
