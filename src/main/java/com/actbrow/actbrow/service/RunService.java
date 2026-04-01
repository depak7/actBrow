package com.actbrow.actbrow.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.actbrow.actbrow.agent.FinalResponseDecision;
import com.actbrow.actbrow.agent.ModelDecision;
import com.actbrow.actbrow.agent.ModelProvider;
import com.actbrow.actbrow.agent.ToolCallDecision;
import com.actbrow.actbrow.agent.ToolDescriptor;
import com.actbrow.actbrow.agent.ToolExecutionResult;
import com.actbrow.actbrow.api.dto.RunResponse;
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
	private final ActbrowProperties properties;
	private final Set<String> startedRuns = ConcurrentHashMap.newKeySet();

	public RunService(RunRepository runRepository, RunStepRepository runStepRepository,
		ConversationService conversationService, AssistantService assistantService, ToolService toolService,
		ModelProvider modelProvider, RunEventBroker eventBroker, PendingClientToolStore pendingClientToolStore,
		BuiltinServerToolExecutor builtinServerToolExecutor, ActbrowProperties properties) {
		this.runRepository = runRepository;
		this.runStepRepository = runStepRepository;
		this.conversationService = conversationService;
		this.assistantService = assistantService;
		this.toolService = toolService;
		this.modelProvider = modelProvider;
		this.eventBroker = eventBroker;
		this.pendingClientToolStore = pendingClientToolStore;
		this.builtinServerToolExecutor = builtinServerToolExecutor;
		this.properties = properties;
	}

	public RunResponse startRun(String conversationId, String userContent) {
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

			for (int stepIndex = 0; stepIndex < properties.maxSteps(); stepIndex++) {
				run.setStepCount(stepIndex + 1);
				runRepository.save(run);

				ModelDecision decision = modelProvider.decideNextStep(assistant.getModel(), assistant.getSystemPrompt(),
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
					"type", tool.type().name(),
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
		if (tool.type() == ToolType.CLIENT) {
			run.setStatus(RunStatus.WAITING_FOR_CLIENT_TOOL);
			runRepository.save(run);
			ToolExecutionResult result = pendingClientToolStore.register(toolCallDecision.toolCall().toolCallId())
				.get(properties.toolTimeout().toMillis(), TimeUnit.MILLISECONDS);
			run.setStatus(RunStatus.IN_PROGRESS);
			runRepository.save(run);
			return result;
		}
		return builtinServerToolExecutor.execute(tool, executionArguments);
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
		return merged;
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
}
