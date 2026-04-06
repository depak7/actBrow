package com.actbrow.actbrow.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

import com.actbrow.actbrow.agent.FinalResponseDecision;
import com.actbrow.actbrow.agent.ModelDecision;
import com.actbrow.actbrow.agent.ModelProvider;
import com.actbrow.actbrow.agent.ToolCallDecision;
import com.actbrow.actbrow.agent.ToolDescriptor;
import com.actbrow.actbrow.agent.ToolExecutionResult;
import com.actbrow.actbrow.api.dto.RunResponse;
import com.actbrow.actbrow.api.dto.TurnRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.actbrow.actbrow.conversation.UserMessageDisplay;
import com.actbrow.actbrow.config.ActbrowProperties;
import com.actbrow.actbrow.model.AssistantDefinitionEntity;
import com.actbrow.actbrow.model.ConversationEntity;
import com.actbrow.actbrow.model.ConversationMessageRole;
import com.actbrow.actbrow.model.RunEntity;
import com.actbrow.actbrow.model.RunStatus;
import com.actbrow.actbrow.model.RunStepEntity;
import com.actbrow.actbrow.model.RunStepType;
import com.actbrow.actbrow.model.ToolType;
import com.actbrow.actbrow.repository.RunRepository;
import com.actbrow.actbrow.repository.RunStepRepository;

@Service
public class RunService {

	private final RunRepository runRepository;
	private final RunStepRepository runStepRepository;
	private final ConversationService conversationService;
	private final AssistantService assistantService;
	private final ToolService toolService;
	private final ModelProvider modelProvider;
	private final RunEventBroker eventBroker;
	private final PendingClientToolStore pendingClientToolStore;
	private final BuiltinServerToolExecutor builtinServerToolExecutor;
	private final HttpServerToolExecutor httpServerToolExecutor;
	private final NavigationFlowService navigationFlowService;
	private final ActbrowProperties properties;
	private final ObjectMapper objectMapper;
	private final Set<String> startedRuns = ConcurrentHashMap.newKeySet();

	public RunService(RunRepository runRepository, RunStepRepository runStepRepository,
		ConversationService conversationService, AssistantService assistantService, ToolService toolService,
		ModelProvider modelProvider, RunEventBroker eventBroker, PendingClientToolStore pendingClientToolStore,
		BuiltinServerToolExecutor builtinServerToolExecutor, HttpServerToolExecutor httpServerToolExecutor,
		NavigationFlowService navigationFlowService, ActbrowProperties properties, ObjectMapper objectMapper) {
		this.runRepository = runRepository;
		this.runStepRepository = runStepRepository;
		this.conversationService = conversationService;
		this.assistantService = assistantService;
		this.toolService = toolService;
		this.modelProvider = modelProvider;
		this.eventBroker = eventBroker;
		this.pendingClientToolStore = pendingClientToolStore;
		this.builtinServerToolExecutor = builtinServerToolExecutor;
		this.httpServerToolExecutor = httpServerToolExecutor;
		this.navigationFlowService = navigationFlowService;
		this.properties = properties;
		this.objectMapper = objectMapper;
	}

	public RunResponse startRun(String conversationId, TurnRequest request) {
		String userContent = composeUserTurnContent(request.content(), request.pageContext());
		ConversationEntity conversation = conversationService.requireConversation(conversationId);
		AssistantDefinitionEntity assistant = assistantService.requireEntity(conversation.getAssistantId());
		conversationService.appendMessage(conversationId, ConversationMessageRole.USER, userContent);

		RunEntity run = new RunEntity();
		run.setConversationId(conversationId);
		run.setAssistantId(assistant.getId());
		run.setStatus(RunStatus.PENDING);
		run.setStepCount(0);
		RunEntity saved = runRepository.save(run);
		return toResponse(saved);
	}

	public void ensureRunStarted(String runId) {
		RunEntity run = requireRun(runId);
		if (run.getStatus() != RunStatus.PENDING) {
			return;
		}
		if (!startedRuns.add(runId)) {
			return;
		}
		Thread.startVirtualThread(() -> processRun(runId));
	}

	public RunEntity requireRun(String runId) {
		return runRepository.findById(runId).orElseThrow(() -> new IllegalArgumentException("Run not found"));
	}

	public RunResponse getRun(String runId) {
		return toResponse(requireRun(runId));
	}

	/**
	 * Removes all runs, steps, messages, and the conversation row. Idempotent if the conversation is already gone.
	 */
	@Transactional
	public void deleteConversationCascade(String conversationId) {
		if (!conversationService.exists(conversationId)) {
			return;
		}
		List<RunEntity> runs = runRepository.findAllByConversationId(conversationId);
		for (RunEntity run : runs) {
			String runId = run.getId();
			startedRuns.remove(runId);
			eventBroker.dispose(runId);
			runStepRepository.deleteByRunId(runId);
		}
		runRepository.deleteAll(runs);
		conversationService.deleteMessagesAndConversation(conversationId);
	}

	public void submitClientToolResult(String runId, String toolCallId, ToolExecutionResult result) {
		requireRun(runId);
		pendingClientToolStore.complete(toolCallId, result);
	}

	private void processRun(String runId) {
		RunEntity run = requireRun(runId);
		eventBroker.emit(runId, "run.started", Map.of(
			"assistantId", run.getAssistantId(),
			"conversationId", run.getConversationId()));
		run.setStatus(RunStatus.IN_PROGRESS);
		runRepository.save(run);

		try {
			AssistantDefinitionEntity assistant = assistantService.requireEntity(run.getAssistantId());
			List<ToolDescriptor> tools = toolService.listDescriptorsForAssistant(assistant.getId());
			String systemPrompt = buildSystemPrompt(assistant, run.getConversationId());

			for (int stepIndex = 0; stepIndex < properties.maxSteps(); stepIndex++) {
				run.setStepCount(stepIndex + 1);
				runRepository.save(run);

				ModelDecision decision = modelProvider.decideNextStep(null, systemPrompt,
					conversationService.listMessages(run.getConversationId()), tools, stepIndex);
				recordStep(runId, stepIndex, RunStepType.MODEL_DECISION, decision.toString());

				if (decision instanceof FinalResponseDecision finalResponse) {
					conversationService.appendMessage(run.getConversationId(), ConversationMessageRole.ASSISTANT,
						finalResponse.message());
					recordStep(runId, stepIndex, RunStepType.FINAL_RESPONSE, finalResponse.message());
					eventBroker.emit(runId, "assistant.message.completed", Map.of("content", finalResponse.message()));
					run.setStatus(RunStatus.COMPLETED);
					run.setCompletedAt(Instant.now());
					runRepository.save(run);
					eventBroker.complete(runId);
					return;
				}

				ToolCallDecision toolCallDecision = (ToolCallDecision) decision;
				ToolDescriptor tool = tools.stream()
					.filter(item -> item.id().equals(toolCallDecision.toolCall().toolId()))
					.findFirst()
					.orElseThrow(() -> new IllegalArgumentException("Unknown tool requested by model"));
				Map<String, Object> executionArguments = mergeArguments(tool, toolCallDecision.toolCall().arguments());
				String executorKey = resolveExecutorKey(tool);
				eventBroker.emit(runId, "tool.call.requested", Map.of(
					"toolCallId", toolCallDecision.toolCall().toolCallId(),
					"toolId", tool.id(),
					"toolKey", tool.key(),
					"executorKey", executorKey,
					"type", wireToolTypeForClients(tool),
					"arguments", executionArguments));
				recordStep(runId, stepIndex, RunStepType.TOOL_CALL, toolCallDecision.toolCall().toString());

				ToolExecutionResult result = executeTool(run, toolCallDecision, tool, executionArguments);
				String toolMessage = result.textSummary() != null ? result.textSummary()
					: result.structuredOutput() != null ? result.structuredOutput() : result.error();
				conversationService.appendMessage(run.getConversationId(), ConversationMessageRole.TOOL, toolMessage);
				recordStep(runId, stepIndex, RunStepType.TOOL_RESULT, result.toString());
				Map<String, Object> payload = new LinkedHashMap<>();
				payload.put("toolCallId", toolCallDecision.toolCall().toolCallId());
				payload.put("success", result.success());
				payload.put("structuredOutput", result.structuredOutput());
				payload.put("textSummary", result.textSummary());
				payload.put("error", result.error());
				eventBroker.emit(runId, "tool.call.completed", payload);
			}

			failRun(run, "Run exceeded max steps");
		}
		catch (Exception exception) {
			failRun(run, exception.getMessage());
		}
	}

	private ToolExecutionResult executeTool(RunEntity run, ToolCallDecision toolCallDecision, ToolDescriptor tool,
		Map<String, Object> executionArguments)
		throws Exception {
		if (ToolCatalogPolicies.executesAsClientPendingTool(tool.type(), tool.executorRef())) {
			run.setStatus(RunStatus.WAITING_FOR_CLIENT_TOOL);
			runRepository.save(run);
			ToolExecutionResult result = pendingClientToolStore.register(toolCallDecision.toolCall().toolCallId())
				.get(properties.toolTimeout().toMillis(), TimeUnit.MILLISECONDS);
			run.setStatus(RunStatus.IN_PROGRESS);
			runRepository.save(run);
			return result;
		}
		if (ToolCatalogPolicies.executesAsHttpTool(tool.type(), tool.executorRef())) {
			return httpServerToolExecutor.execute(tool, executionArguments);
		}
		return builtinServerToolExecutor.execute(tool, executionArguments);
	}

	private static String wireToolTypeForClients(ToolDescriptor tool) {
		if (ToolCatalogPolicies.executesAsClientPendingTool(tool.type(), tool.executorRef())) {
			return ToolType.CLIENT.name();
		}
		if (ToolCatalogPolicies.executesAsHttpTool(tool.type(), tool.executorRef())) {
			return ToolType.SERVER_HTTP.name();
		}
		return tool.type().name();
	}

	private String resolveExecutorKey(ToolDescriptor tool) {
		return tool.executorRef() == null || tool.executorRef().isBlank() ? tool.key() : tool.executorRef();
	}

	private Map<String, Object> mergeArguments(ToolDescriptor tool, Map<String, Object> runtimeArguments) {
		Map<String, Object> merged = new LinkedHashMap<>();
		if (tool.defaultArguments() != null) {
			merged.putAll(tool.defaultArguments());
		}
		if (runtimeArguments != null) {
			merged.putAll(runtimeArguments);
		}
		// Dedicated nav tools (e.g. app.navigate.profile) carry a fixed path; do not let the model override it.
		if (isDedicatedClientNavigateTool(tool)) {
			Object fixedPath = tool.defaultArguments().get("path");
			if (fixedPath != null && !String.valueOf(fixedPath).isBlank()) {
				merged.put("path", fixedPath);
			}
		}
		return merged;
	}

	private static boolean isDedicatedClientNavigateTool(ToolDescriptor tool) {
		if (!ToolCatalogPolicies.executesAsClientPendingTool(tool.type(), tool.executorRef())) {
			return false;
		}
		if (!"app.navigate".equals(tool.executorRef())) {
			return false;
		}
		if ("app.navigate".equals(tool.key())) {
			return false;
		}
		Map<String, Object> defs = tool.defaultArguments();
		return defs != null && defs.containsKey("path") && defs.get("path") != null
			&& !String.valueOf(defs.get("path")).isBlank();
	}

	private void failRun(RunEntity run, String error) {
		run.setStatus(RunStatus.FAILED);
		run.setLastError(error);
		run.setCompletedAt(Instant.now());
		runRepository.save(run);
		eventBroker.emit(run.getId(), "run.failed", Map.of("message", error));
		eventBroker.complete(run.getId());
	}

	private void recordStep(String runId, int stepIndex, RunStepType type, String payload) {
		RunStepEntity step = new RunStepEntity();
		step.setRunId(runId);
		step.setStepIndex(stepIndex);
		step.setType(type);
		step.setPayload(payload);
		runStepRepository.save(step);
	}

	private RunResponse toResponse(RunEntity run) {
		return new RunResponse(run.getId(), run.getConversationId(), run.getAssistantId(), run.getStatus(),
			run.getStepCount(), run.getLastError(), run.getCreatedAt(), run.getCompletedAt());
	}

	private String composeUserTurnContent(String content, Map<String, Object> pageContext) {
		if (pageContext == null || pageContext.isEmpty()) {
			return content;
		}
		try {
			String json = objectMapper.writeValueAsString(pageContext);
			if (json.length() > 48_000) {
				json = json.substring(0, 48_000) + "\n...(PAGE_CONTEXT truncated)";
			}
			return content.stripTrailing() + UserMessageDisplay.PAGE_CONTEXT_APPENDIX_START
				+ "Prefer the listed \"selector\" values for dom.click, dom.type, dom.read. "
				+ "If you need a control not listed, call dom.query (or page.screenshot) first—do not invent selectors.) ---\n"
				+ json;
		}
		catch (JsonProcessingException exception) {
			return content;
		}
	}

	private String buildSystemPrompt(AssistantDefinitionEntity assistant, String conversationId) {
		StringBuilder prompt = new StringBuilder();
		
		if (assistant.getSystemPrompt() != null && !assistant.getSystemPrompt().isBlank()) {
			prompt.append(assistant.getSystemPrompt()).append("\n\n");
		}

		prompt.append("BROWSER AUTOMATION: You do not see the live page. User messages may include a PAGE_CONTEXT JSON block from the host app listing interactive elements and suggested CSS selectors. Treat that block as authoritative for that turn unless tool results show the DOM changed. Do not guess selectors; use PAGE_CONTEXT or call dom.query / page.screenshot first, then act with dom.click, dom.type, or app.navigate.\n\n");

		if (assistant.isUsePredefinedFlows()) {
			try {
				var flows = navigationFlowService.listEnabledFlows(assistant.getId());
				if (!flows.isEmpty()) {
					prompt.append("NAVIGATION FLOWS AVAILABLE:\n");
					prompt.append("When the user request matches a flow's trigger phrase, follow the defined steps in order.\n\n");
					
					for (var flow : flows) {
						prompt.append("Flow: ").append(flow.name()).append("\n");
						prompt.append("Trigger: ").append(flow.triggerPhrase()).append("\n");
						prompt.append("Steps:\n");
						for (int i = 0; i < flow.steps().size(); i++) {
							var step = flow.steps().get(i);
							prompt.append("  ").append(i + 1).append(". ").append(step.action())
								.append(" -> ").append(step.target());
							if (step.description() != null && !step.description().isBlank()) {
								prompt.append(" (").append(step.description()).append(")");
							}
							prompt.append("\n");
						}
						prompt.append("\n");
					}
					
					prompt.append("INSTRUCTION: When user request matches a flow trigger, execute each step sequentially using the appropriate tools (app.navigate, dom.click, dom.type, etc.). Do not skip steps.\n\n");
				}
			}
			catch (Exception e) {
				// Ignore errors in loading flows, continue with basic prompt
			}
		}

		return prompt.toString();
	}
}
