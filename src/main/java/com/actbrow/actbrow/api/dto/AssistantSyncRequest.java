package com.actbrow.actbrow.api.dto;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

public record AssistantSyncRequest(
	@Valid AssistantConfig assistant,
	List<String> origins,
	@Valid List<NavigationTool> navigation,
	@Valid List<HttpTool> httpTools,
	@Valid List<FlowConfig> flows,
	@Valid List<KnowledgeConfig> knowledge
) {
	public record AssistantConfig(
		String systemPrompt,
		Boolean usePredefinedFlows
	) {
	}

	public record NavigationTool(
		@NotBlank String key,
		@NotBlank String path,
		@NotBlank String displayName,
		String description,
		Boolean enabled
	) {
	}

	public record HttpTool(
		@NotBlank String key,
		@NotBlank String displayName,
		@NotBlank String description,
		Map<String, Object> inputSchema,
		Map<String, Object> metadata,
		Boolean enabled
	) {
	}

	public record FlowConfig(
		@NotBlank String name,
		@NotBlank String triggerPhrase,
		@Valid List<NavigationFlowRequest.NavigationStep> steps,
		Boolean enabled
	) {
	}

	public record KnowledgeConfig(
		@NotBlank String title,
		@NotBlank String content,
		String source,
		Boolean enabled
	) {
	}
}
