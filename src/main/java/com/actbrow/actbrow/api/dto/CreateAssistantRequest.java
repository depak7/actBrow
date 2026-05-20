package com.actbrow.actbrow.api.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateAssistantRequest(
	@NotBlank String name,
	String systemPrompt,
	/** Provider model id (e.g. {@code gemini-2.5-flash}, {@code gpt-4o}, {@code anthropic/claude-sonnet-4}); blank uses {@code spring.ai.openai.chat.options.model}. */
	String model,
	boolean usePredefinedFlows,
	@NotBlank String userId
) {
}
