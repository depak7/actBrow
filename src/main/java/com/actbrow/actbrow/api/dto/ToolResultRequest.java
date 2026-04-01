package com.actbrow.actbrow.api.dto;

import jakarta.validation.constraints.NotBlank;

public record ToolResultRequest(
	@NotBlank String toolCallId,
	boolean success,
	String structuredOutput,
	String textSummary,
	String error
) {
}
