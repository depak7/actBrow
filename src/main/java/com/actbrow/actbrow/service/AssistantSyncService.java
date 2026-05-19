package com.actbrow.actbrow.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.actbrow.actbrow.api.dto.AssistantSyncRequest;
import com.actbrow.actbrow.api.dto.AssistantSyncResponse;
import com.actbrow.actbrow.api.dto.NavigationFlowRequest;
import com.actbrow.actbrow.api.dto.ToolRequest;
import com.actbrow.actbrow.model.AssistantDefinitionEntity;
import com.actbrow.actbrow.model.ToolType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class AssistantSyncService {

	private final AssistantService assistantService;
	private final ToolService toolService;
	private final NavigationFlowService navigationFlowService;
	private final KnowledgeService knowledgeService;
	private final EmbedSnippetService embedSnippetService;
	private final ObjectMapper objectMapper;
	private final String publicBaseUrl;

	public AssistantSyncService(AssistantService assistantService, ToolService toolService,
		NavigationFlowService navigationFlowService, KnowledgeService knowledgeService,
		EmbedSnippetService embedSnippetService, ObjectMapper objectMapper,
		@Value("${actbrow.public.base-url:http://localhost:8080}") String publicBaseUrl) {
		this.assistantService = assistantService;
		this.toolService = toolService;
		this.navigationFlowService = navigationFlowService;
		this.knowledgeService = knowledgeService;
		this.embedSnippetService = embedSnippetService;
		this.objectMapper = objectMapper;
		this.publicBaseUrl = publicBaseUrl;
	}

	@Transactional
	public AssistantSyncResponse sync(String assistantId, AssistantSyncRequest request) {
		AssistantDefinitionEntity assistant = assistantService.requireEntity(assistantId);
		assistantService.ensureConnectKeys(assistant);

		Map<String, AssistantSyncResponse.SyncCounts> summary = new LinkedHashMap<>();
		if (request.assistant() != null) {
			applyAssistantConfig(assistant, request.assistant());
		}
		if (request.origins() != null) {
			assistant.setAllowedOriginsJson(writeJson(request.origins()));
		}

		summary.put("navigation", syncNavigation(assistantId, request.navigation()));
		summary.put("httpTools", syncHttpTools(assistantId, request.httpTools()));
		summary.put("flows", syncFlows(assistant, request.flows()));
		summary.put("knowledge", syncKnowledge(assistantId, request.knowledge()));

		Instant syncedAt = Instant.now();
		assistant.setLastSyncedAt(syncedAt);
		assistant.setLastSyncSummaryJson(writeJson(summary));
		assistantService.saveEntity(assistant);

		return new AssistantSyncResponse(
			syncedAt,
			summary,
			assistant.getWidgetKey(),
			embedSnippetService.buildSnippet(publicBaseUrl, assistant.getId(), assistant.getWidgetKey()));
	}

	private void applyAssistantConfig(AssistantDefinitionEntity assistant,
		AssistantSyncRequest.AssistantConfig config) {
		if (config.systemPrompt() != null) {
			assistant.setSystemPrompt(config.systemPrompt());
		}
		if (config.usePredefinedFlows() != null) {
			assistant.setUsePredefinedFlows(config.usePredefinedFlows());
		}
	}

	private AssistantSyncResponse.SyncCounts syncNavigation(String assistantId,
		List<AssistantSyncRequest.NavigationTool> navigation) {
		if (navigation == null || navigation.isEmpty()) {
			return new AssistantSyncResponse.SyncCounts(0, 0);
		}
		int created = 0;
		int updated = 0;
		for (AssistantSyncRequest.NavigationTool nav : navigation) {
			boolean existed = toolService.findByKey(nav.key()).isPresent();
			ToolRequest toolRequest = new ToolRequest(
				nav.key(),
				nav.displayName(),
				nav.description() == null || nav.description().isBlank()
					? "Navigate to " + nav.path()
					: nav.description(),
				Map.of("type", "object", "properties", Map.of()),
				null,
				ToolType.CLIENT,
				"1",
				nav.enabled() == null || nav.enabled(),
				"app.navigate",
				Map.of("path", nav.path()),
				Map.of());
			toolService.upsertByKey(toolRequest);
			toolService.attachToolIfAbsent(assistantId, nav.key());
			if (existed) {
				updated++;
			}
			else {
				created++;
			}
		}
		return new AssistantSyncResponse.SyncCounts(created, updated);
	}

	private AssistantSyncResponse.SyncCounts syncHttpTools(String assistantId,
		List<AssistantSyncRequest.HttpTool> httpTools) {
		if (httpTools == null || httpTools.isEmpty()) {
			return new AssistantSyncResponse.SyncCounts(0, 0);
		}
		int created = 0;
		int updated = 0;
		for (AssistantSyncRequest.HttpTool httpTool : httpTools) {
			boolean existed = toolService.findByKey(httpTool.key()).isPresent();
			Map<String, Object> inputSchema = httpTool.inputSchema() == null
				? Map.of("type", "object", "properties", Map.of())
				: httpTool.inputSchema();
			Map<String, Object> metadata = httpTool.metadata() == null ? Map.of() : httpTool.metadata();
			ToolRequest toolRequest = new ToolRequest(
				httpTool.key(),
				httpTool.displayName(),
				httpTool.description(),
				inputSchema,
				null,
				ToolType.SERVER_HTTP,
				"1",
				httpTool.enabled() == null || httpTool.enabled(),
				httpTool.key(),
				Map.of(),
				metadata);
			toolService.upsertByKey(toolRequest);
			toolService.attachToolIfAbsent(assistantId, httpTool.key());
			if (existed) {
				updated++;
			}
			else {
				created++;
			}
		}
		return new AssistantSyncResponse.SyncCounts(created, updated);
	}

	private AssistantSyncResponse.SyncCounts syncFlows(AssistantDefinitionEntity assistant,
		List<AssistantSyncRequest.FlowConfig> flows) {
		if (flows == null || flows.isEmpty()) {
			return new AssistantSyncResponse.SyncCounts(0, 0);
		}
		int created = 0;
		int updated = 0;
		for (AssistantSyncRequest.FlowConfig flow : flows) {
			List<NavigationFlowRequest.NavigationStep> steps = flow.steps() == null
				? List.of()
				: flow.steps();
			NavigationFlowRequest flowRequest = new NavigationFlowRequest(
				flow.name(),
				flow.triggerPhrase(),
				steps,
				flow.enabled() == null || flow.enabled());
			if (navigationFlowService.upsertByName(assistant.getId(), flowRequest, assistant)) {
				updated++;
			}
			else {
				created++;
			}
		}
		return new AssistantSyncResponse.SyncCounts(created, updated);
	}

	private AssistantSyncResponse.SyncCounts syncKnowledge(String assistantId,
		List<AssistantSyncRequest.KnowledgeConfig> knowledge) {
		if (knowledge == null || knowledge.isEmpty()) {
			return new AssistantSyncResponse.SyncCounts(0, 0);
		}
		int created = 0;
		int updated = 0;
		for (AssistantSyncRequest.KnowledgeConfig doc : knowledge) {
			if (knowledgeService.upsertByTitle(assistantId, doc.title(), doc.content(), doc.source(),
				doc.enabled() == null || doc.enabled())) {
				updated++;
			}
			else {
				created++;
			}
		}
		return new AssistantSyncResponse.SyncCounts(created, updated);
	}

	private String writeJson(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalArgumentException("Unable to serialize sync metadata", exception);
		}
	}
}
