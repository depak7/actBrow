package com.actbrow.actbrow.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.actbrow.actbrow.api.dto.AssistantConnectResponse;
import com.actbrow.actbrow.api.dto.AssistantSyncRequest;
import com.actbrow.actbrow.api.dto.AssistantSyncResponse;
import com.actbrow.actbrow.model.AssistantDefinitionEntity;

@Service
public class AssistantConnectService {

	private final AssistantService assistantService;
	private final AssistantSyncService assistantSyncService;
	private final AssistantSetupPromptService setupPromptService;
	private final EmbedSnippetService embedSnippetService;
	private final String publicBaseUrl;

	public AssistantConnectService(AssistantService assistantService, AssistantSyncService assistantSyncService,
		AssistantSetupPromptService setupPromptService, EmbedSnippetService embedSnippetService,
		@Value("${actbrow.public.base-url:http://localhost:8080}") String publicBaseUrl) {
		this.assistantService = assistantService;
		this.assistantSyncService = assistantSyncService;
		this.setupPromptService = setupPromptService;
		this.embedSnippetService = embedSnippetService;
		this.publicBaseUrl = publicBaseUrl;
	}

	public AssistantConnectResponse getConnect(String assistantId, String userId) {
		AssistantDefinitionEntity assistant = assistantService.requireOwnedEntity(assistantId, userId);
		assistantService.ensureConnectKeys(assistant);
		assistantService.saveEntity(assistant);
		return toConnectResponse(assistant);
	}

	public AssistantSyncResponse sync(String assistantId, String userId, String authType, String authAssistantId,
		AssistantSyncRequest request) {
		validateSyncAuth(assistantId, userId, authType, authAssistantId);
		return assistantSyncService.sync(assistantId, request);
	}

	private void validateSyncAuth(String assistantId, String userId, String authType, String authAssistantId) {
		if ("setup".equals(authType)) {
			if (!assistantId.equals(authAssistantId)) {
				throw new IllegalArgumentException("Setup key is not authorized for this assistant");
			}
			return;
		}
		if (userId == null || userId.isBlank()) {
			throw new IllegalArgumentException("Unauthorized");
		}
		assistantService.requireOwnedEntity(assistantId, userId);
	}

	private AssistantConnectResponse toConnectResponse(AssistantDefinitionEntity assistant) {
		String embedSnippet = assistant.getWidgetKey() == null
			? null
			: embedSnippetService.buildSnippet(publicBaseUrl, assistant.getId(), assistant.getWidgetKey());
		return new AssistantConnectResponse(
			assistant.getId(),
			assistant.getName(),
			publicBaseUrl,
			assistant.getSetupKey(),
			assistant.getWidgetKey(),
			setupPromptService.buildSetupPrompt(assistant, publicBaseUrl, assistant.getSetupKey()),
			assistant.getLastSyncedAt(),
			setupPromptService.parseSummary(assistant),
			setupPromptService.parseOrigins(assistant),
			embedSnippet);
	}
}
