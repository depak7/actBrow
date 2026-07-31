package com.actbrow.actbrow.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.actbrow.actbrow.api.dto.ActivationStatusResponse;
import com.actbrow.actbrow.api.dto.ActivationStatusResponse.ActivationStep;
import com.actbrow.actbrow.model.AssistantDefinitionEntity;
import com.actbrow.actbrow.model.ToolType;
import com.actbrow.actbrow.repository.ApiIntegrationRepository;
import com.actbrow.actbrow.repository.ConversationRepository;
import com.actbrow.actbrow.repository.KnowledgeDocumentRepository;
import com.actbrow.actbrow.repository.McpServerRepository;
import com.actbrow.actbrow.repository.RunRepository;

@Service
public class ActivationStatusService {

	private final AssistantService assistantService;
	private final ToolService toolService;
	private final ApiIntegrationRepository apiIntegrationRepository;
	private final McpServerRepository mcpServerRepository;
	private final KnowledgeDocumentRepository knowledgeDocumentRepository;
	private final ConversationRepository conversationRepository;
	private final RunRepository runRepository;
	private final EmbedSnippetService embedSnippetService;
	private final String publicBaseUrl;

	public ActivationStatusService(AssistantService assistantService, ToolService toolService,
		ApiIntegrationRepository apiIntegrationRepository, McpServerRepository mcpServerRepository,
		KnowledgeDocumentRepository knowledgeDocumentRepository, ConversationRepository conversationRepository,
		RunRepository runRepository, EmbedSnippetService embedSnippetService,
		@Value("${actbrow.public.base-url:http://localhost:8080}") String publicBaseUrl) {
		this.assistantService = assistantService;
		this.toolService = toolService;
		this.apiIntegrationRepository = apiIntegrationRepository;
		this.mcpServerRepository = mcpServerRepository;
		this.knowledgeDocumentRepository = knowledgeDocumentRepository;
		this.conversationRepository = conversationRepository;
		this.runRepository = runRepository;
		this.embedSnippetService = embedSnippetService;
		this.publicBaseUrl = publicBaseUrl;
	}

	public ActivationStatusResponse status(String assistantId, String userId) {
		AssistantDefinitionEntity assistant = assistantService.requireOwnedEntity(assistantId, userId);
		assistantService.ensureConnectKeys(assistant);
		assistantService.saveEntity(assistant);

		boolean hasSync = assistant.getLastSyncedAt() != null;
		boolean hasHttpTools = toolService.listAssistantTools(assistantId).stream()
			.anyMatch(tool -> tool.type() == ToolType.SERVER_HTTP || tool.type() == ToolType.CLIENT
				|| tool.type() == ToolType.MCP);
		boolean hasIntegration = !apiIntegrationRepository.findAllByAssistantIdOrderByCreatedAtDesc(assistantId)
			.isEmpty()
			|| !mcpServerRepository.findAllByAssistantIdOrderByCreatedAtDesc(assistantId).isEmpty();
		boolean hasKnowledge = !knowledgeDocumentRepository
			.findAllByAssistantIdOrderByUpdatedAtDesc(assistantId).isEmpty();
		boolean hasEmbedKeys = assistant.getWidgetKey() != null && !assistant.getWidgetKey().isBlank();
		boolean hasConversation = !conversationRepository.findAllByAssistantId(assistantId).isEmpty();
		boolean hasSuccessfulRun = runRepository.countByAssistantIdAndStatus(assistantId,
			com.actbrow.actbrow.model.RunStatus.COMPLETED) > 0;

		List<ActivationStep> steps = new ArrayList<>();
		steps.add(new ActivationStep("assistant", "Assistant created", "You have an assistant ready to configure.",
			true, "/dashboard/assistants"));
		steps.add(new ActivationStep("wire", "Wire tools or sync",
			"Paste the Connect setup prompt in your coding agent, import OpenAPI, or connect MCP.",
			hasSync || hasHttpTools || hasIntegration, "/dashboard/connect"));
		steps.add(new ActivationStep("knowledge", "Add knowledge (optional)",
			"Upload policies or product facts so answers stay grounded.", hasKnowledge,
			"/dashboard/knowledge"));
		steps.add(new ActivationStep("embed", "Embed credentials ready",
			"Widget key and embed snippet are ready. Paste the snippet into your app (completion is verified by a first successful run).",
			hasEmbedKeys, "/dashboard/connect"));
		steps.add(new ActivationStep("first-run", "First successful run",
			"Open the widget in your app and complete one real ask.", hasSuccessfulRun,
			"/dashboard/conversations"));

		int done = (int) steps.stream().filter(ActivationStep::done).count();
		boolean ready = hasEmbedKeys && (hasSync || hasHttpTools || hasIntegration) && hasSuccessfulRun;
		String embed = hasEmbedKeys
			? embedSnippetService.buildSnippet(publicBaseUrl, assistant.getId(), assistant.getWidgetKey(),
				null)
			: null;
		String magic = "https://your-app.example.com/app?actbrow_open=1&actbrow_prompt="
			+ java.net.URLEncoder.encode("Help me get started", java.nio.charset.StandardCharsets.UTF_8);

		return new ActivationStatusResponse(assistant.getId(), assistant.getName(), done, steps.size(),
			ready, steps, embed, magic);
	}
}
