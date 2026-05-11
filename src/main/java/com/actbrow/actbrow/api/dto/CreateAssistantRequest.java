package com.actbrow.actbrow.api.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateAssistantRequest(
	@NotBlank String name,
	String systemPrompt,
	/** Gemini chat model id (e.g. {@code gemini-2.5-flash}); blank uses {@code spring.ai.openai.chat.options.model}. */
	String model,
	boolean usePredefinedFlows,
	@NotBlank String userId
) {
}
