package com.actbrow.actbrow.api.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateAssistantRequest(
	@NotBlank String name,
	String systemPrompt,
	/** DeepSeek chat model id (e.g. {@code deepseek-chat}); blank uses {@code spring.ai.openai.chat.options.model}. */
	String model,
	boolean usePredefinedFlows,
	@NotBlank String userId
) {
}
