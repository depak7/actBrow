package com.actbrow.actbrow.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

import com.actbrow.actbrow.agent.FinalResponseDecision;
import com.actbrow.actbrow.agent.ModelDecision;
import com.actbrow.actbrow.agent.ModelProvider;
import com.actbrow.actbrow.agent.ToolCall;
import com.actbrow.actbrow.agent.ToolCallDecision;
import com.actbrow.actbrow.agent.ToolDescriptor;
import com.actbrow.actbrow.agent.ToolExecutionResult;
import com.actbrow.actbrow.api.dto.RunResponse;
import com.actbrow.actbrow.api.dto.TurnRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.actbrow.actbrow.conversation.PageContextParser;
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

	private static final Logger log = LoggerFactory.getLogger(RunService.class);

	@Value("${spring.ai.openai.chat.options.model}")
	private String model;

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
	private final KnowledgeSearchToolExecutor knowledgeSearchToolExecutor;
	private final ActbrowProperties properties;
	private final ObjectMapper objectMapper;
	private final String defaultChatModel;
	private final Set<String> startedRuns = ConcurrentHashMap.newKeySet();
	private final Set<String> cancelledRuns = ConcurrentHashMap.newKeySet();
	private final Map<String, Set<String>> seenToolCalls = new ConcurrentHashMap<>();

	public RunService(RunRepository runRepository, RunStepRepository runStepRepository,
		ConversationService conversationService, AssistantService assistantService, ToolService toolService,
		ModelProvider modelProvider, RunEventBroker eventBroker, PendingClientToolStore pendingClientToolStore,
		BuiltinServerToolExecutor builtinServerToolExecutor, HttpServerToolExecutor httpServerToolExecutor,
		NavigationFlowService navigationFlowService, KnowledgeSearchToolExecutor knowledgeSearchToolExecutor,
		ActbrowProperties properties, ObjectMapper objectMapper,
		@Value("${spring.ai.openai.chat.options.model:gemini-2.5-flash}") String defaultChatModel) {
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
		this.knowledgeSearchToolExecutor = knowledgeSearchToolExecutor;
		this.properties = properties;
		this.objectMapper = objectMapper;
		this.defaultChatModel = defaultChatModel;
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

	public RunResponse cancelRun(String runId) {
		RunEntity run = requireRun(runId);
		RunStatus status = run.getStatus();
		if (status == RunStatus.COMPLETED || status == RunStatus.FAILED || status == RunStatus.CANCELLED) {
			return toResponse(run);
		}
		cancelledRuns.add(runId);
		return toResponse(run);
	}

	public RunEntity requireRun(String runId) {
		return runRepository.findById(runId).orElseThrow(() -> new IllegalArgumentException("Run not found"));
	}

	public RunResponse getRun(String runId) {
		return toResponse(requireRun(runId));
	}

	/**
	 * Removes all runs, steps, messages, and the conversation row. Idempotent if the conversation is already gone.
	 *
	 * Also releases any pending client-tool futures for the deleted runs so virtual threads waiting
	 * on a tool result unblock immediately instead of timing out and attempting to save against a
	 * row that no longer exists.
	 */
	@Transactional
	public void deleteConversationCascade(String conversationId) {
		if (!conversationService.exists(conversationId)) {
			return;
		}
		List<RunEntity> runs = runRepository.findAllByConversationId(conversationId);
		for (RunEntity run : runs) {
			String runId = run.getId();
			cancelledRuns.add(runId);
			pendingClientToolStore.cancelByRunId(runId);
			startedRuns.remove(runId);
			eventBroker.dispose(runId);
			runStepRepository.deleteByRunId(runId);
		}
		runRepository.deleteAll(runs);
		conversationService.deleteMessagesAndConversation(conversationId);
		for (RunEntity run : runs) {
			cancelledRuns.remove(run.getId());
		}
	}

	public void submitClientToolResult(String runId, String toolCallId, ToolExecutionResult result) {
		requireRun(runId);
		pendingClientToolStore.complete(toolCallId, result);
	}

	private void processRun(String runId) {
		RunEntity run = requireRun(runId);

		if (cancelledRuns.contains(runId)) {
			cancelRunInternal(run);
			return;
		}

		eventBroker.emit(runId, "run.started", Map.of(
			"assistantId", run.getAssistantId(),
			"conversationId", run.getConversationId()));
		run.setStatus(RunStatus.IN_PROGRESS);
		runRepository.save(run);

		try {
			AssistantDefinitionEntity assistant = assistantService.requireEntity(run.getAssistantId());
			List<ToolDescriptor> tools = toolService.listDescriptorsForAssistant(assistant.getId());
			String systemPrompt = buildSystemPrompt(assistant, run.getConversationId());
			boolean navigatedThisRun = false;

			for (int stepIndex = 0; stepIndex < properties.maxSteps(); stepIndex++) {
				if (cancelledRuns.contains(runId)) {
					cancelRunInternal(run);
					return;
				}

				run.setStepCount(stepIndex + 1);
				runRepository.save(run);

				String chatModel = model;
				if (chatModel == null || chatModel.isBlank()) {
					chatModel = defaultChatModel;
				}
				ModelDecision decision = modelProvider.decideNextStep(chatModel, systemPrompt,
					conversationService.listMessages(run.getConversationId()), tools, stepIndex);
				recordStep(runId, stepIndex, RunStepType.MODEL_DECISION, decision.toString());

				if (decision instanceof FinalResponseDecision finalResponse) {
					conversationService.appendMessage(run.getConversationId(), ConversationMessageRole.ASSISTANT,
						finalResponse.message());
					recordStep(runId, stepIndex, RunStepType.FINAL_RESPONSE, finalResponse.message());
					Map<String, Object> completedPayload = new LinkedHashMap<>();
					ClarificationResponseParser.ParsedClarification clarification = ClarificationResponseParser
						.parse(finalResponse.message());
					if (clarification != null) {
						completedPayload.put("content", clarification.visibleContent());
						completedPayload.put("clarification", true);
						completedPayload.put("options", clarification.options());
						completedPayload.put("recommendedOption", clarification.recommendedOption());
					}
					else {
						completedPayload.put("content", finalResponse.message());
					}
					eventBroker.emit(runId, "assistant.message.completed", completedPayload);
					run.setStatus(RunStatus.COMPLETED);
					run.setCompletedAt(Instant.now());
					runRepository.save(run);
					eventBroker.complete(runId);
					return;
				}

				ToolCallDecision toolCallDecision = (ToolCallDecision) decision;

				// Store the ASSISTANT tool-calls message so providers can reconstruct valid API history
				conversationService.appendMessage(run.getConversationId(), ConversationMessageRole.ASSISTANT,
					buildAssistantToolCallsJson(toolCallDecision.toolCalls()));

				// Execute each tool call sequentially within this step
				for (ToolCall toolCall : toolCallDecision.toolCalls()) {
					if (cancelledRuns.contains(runId)) {
						cancelRunInternal(run);
						return;
					}
					ToolDescriptor tool = tools.stream()
						.filter(item -> item.id().equals(toolCall.toolId()))
						.findFirst()
						.orElseThrow(() -> new IllegalArgumentException("Unknown tool requested by model: " + toolCall.toolKey()));
					Map<String, Object> executionArguments = mergeArguments(tool, toolCall.arguments());
					String executorKey = resolveExecutorKey(tool);
					boolean deferNavigation = navigatedThisRun && isNavigateTool(tool);

					ToolExecutionResult result;
					if (deferNavigation) {
						recordStep(runId, stepIndex, RunStepType.TOOL_CALL, toolCall.toString() + " [deferred]");
						result = navigationDeferredResult(tool.key());
					}
					else {
						Map<String, Object> requestedPayload = new LinkedHashMap<>();
						requestedPayload.put("toolCallId", toolCall.toolCallId());
						requestedPayload.put("toolId", tool.id());
						requestedPayload.put("toolKey", tool.key());
						requestedPayload.put("executorKey", executorKey);
						requestedPayload.put("type", wireToolTypeForClients(tool));
						requestedPayload.put("arguments", executionArguments);
						if (ToolCatalogPolicies.executesAsBrowserHttpTool(tool)) {
							requestedPayload.put("http", browserHttpPayload(tool, executionArguments));
						}
						eventBroker.emit(runId, "tool.call.requested", requestedPayload);
						recordStep(runId, stepIndex, RunStepType.TOOL_CALL, toolCall.toString());

						result = executeTool(run, toolCall, tool, executionArguments);
						if (result.success() && isNavigateTool(tool)) {
							navigatedThisRun = true;
						}
					}
					String toolMessage = toolResultContentForModel(result);
					// Store TOOL message with its toolCallId so providers can pair it with the ASSISTANT entry
					conversationService.appendMessage(run.getConversationId(), ConversationMessageRole.TOOL, toolMessage,
						toolCall.toolCallId());
					recordStep(runId, stepIndex, RunStepType.TOOL_RESULT, result.toString());
					Map<String, Object> payload = new LinkedHashMap<>();
					payload.put("toolCallId", toolCall.toolCallId());
					payload.put("success", result.success());
					payload.put("structuredOutput", result.structuredOutput());
					payload.put("textSummary", result.textSummary());
					payload.put("error", result.error());
					eventBroker.emit(runId, "tool.call.completed", payload);
				}
			}

			failRun(run, "Run exceeded max steps");
		}
		catch (java.util.concurrent.CancellationException cancellation) {
			// Run was cancelled externally (e.g. conversation deleted while a client tool was pending).
			// The conversation and run rows are already gone — do not attempt any further DB writes.
		}
		catch (Exception exception) {
			if (cancelledRuns.contains(runId)) {
				// Exception surfaced from a cancelled run — swallow to avoid writing to a deleted row.
				return;
			}
			failRun(run, exception.getMessage(), exception);
		}
		finally {
			startedRuns.remove(runId);
			cancelledRuns.remove(runId);
			seenToolCalls.remove(runId);
		}
	}

	private void cancelRunInternal(RunEntity run) {
		run.setStatus(RunStatus.CANCELLED);
		run.setCompletedAt(Instant.now());
		runRepository.save(run);
		eventBroker.emit(run.getId(), "run.cancelled", Map.of());
		eventBroker.complete(run.getId());
	}

	private ToolExecutionResult executeTool(RunEntity run, ToolCall toolCall, ToolDescriptor tool,
		Map<String, Object> executionArguments)
		throws Exception {
		String signature = toolCallSignature(tool, executionArguments);
		Set<String> seen = seenToolCalls.computeIfAbsent(run.getId(), k -> ConcurrentHashMap.newKeySet());
		if (!seen.add(signature)) {
			String message = "You already called " + tool.key()
				+ " with these exact arguments earlier in this run. The result will not change. "
				+ "Stop calling tools and produce a final answer for the user instead.";
			return new ToolExecutionResult(false, null, message, message);
		}
		if (ToolCatalogPolicies.executesAsClientPendingTool(tool.type(), tool.executorRef())
			|| ToolCatalogPolicies.executesAsBrowserHttpTool(tool)) {
			run.setStatus(RunStatus.WAITING_FOR_CLIENT_TOOL);
			runRepository.save(run);
			try {
				ToolExecutionResult result = pendingClientToolStore.register(run.getId(), toolCall.toolCallId())
					.get(properties.toolTimeout().toMillis(), TimeUnit.MILLISECONDS);
				run.setStatus(RunStatus.IN_PROGRESS);
				runRepository.save(run);
				return result;
			}
			catch (TimeoutException e) {
				// Client never posted a tool result (e.g. hard page refresh killed the SDK mid-navigation).
				// Return a synthetic failure so the caller still appends a TOOL message paired with the
				// assistant tool_calls entry. Without this, history would be left with an orphan tool_calls
				// and OpenAI rejects the next turn.
				pendingClientToolStore.cancel(toolCall.toolCallId());
				run.setStatus(RunStatus.IN_PROGRESS);
				runRepository.save(run);
				String message = "Client tool '" + tool.key() + "' timed out after "
					+ properties.toolTimeout().toMillis() + "ms. You did NOT receive any data from it. "
					+ "Do not describe page content. Tell the user this tool could not complete and stop.";
				return new ToolExecutionResult(false, null, message, message);
			}
		}
		if (ToolCatalogPolicies.executesAsKnowledgeSearch(tool.type(), tool.executorRef())) {
			log.info("LLM requested knowledge.search runId={} conversationId={} assistantId={} arguments={}",
				run.getId(), run.getConversationId(), run.getAssistantId(), executionArguments);
			return knowledgeSearchToolExecutor.execute(run.getAssistantId(),
				mergeKnowledgeSearchArguments(run.getConversationId(), executionArguments));
		}
		if (ToolCatalogPolicies.executesAsHttpTool(tool.type(), tool.executorRef())) {
			return httpServerToolExecutor.execute(tool, executionArguments);
		}
		return builtinServerToolExecutor.execute(tool, executionArguments);
	}

	/**
	 * Text stored on the TOOL conversation row for the next model turn. Prefer structured payloads (JSON
	 * bodies from HTTP/browser tools, observation snapshots) over short summaries — otherwise the model
	 * only sees e.g. "Browser HTTP GET … returned 200" with no response body.
	 */
	private static String toolResultContentForModel(ToolExecutionResult result) {
		if (result.structuredOutput() != null && !result.structuredOutput().isBlank()) {
			return result.structuredOutput();
		}
		if (result.textSummary() != null && !result.textSummary().isBlank()) {
			return result.textSummary();
		}
		String err = result.error();
		return err != null ? err : "";
	}

	private static String wireToolTypeForClients(ToolDescriptor tool) {
		if (ToolCatalogPolicies.executesAsBrowserHttpTool(tool)) {
			return "BROWSER_HTTP";
		}
		if (ToolCatalogPolicies.executesAsClientPendingTool(tool.type(), tool.executorRef())) {
			return ToolType.CLIENT.name();
		}
		if (ToolCatalogPolicies.executesAsHttpTool(tool.type(), tool.executorRef())) {
			return ToolType.SERVER_HTTP.name();
		}
		return tool.type().name();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> browserHttpPayload(ToolDescriptor tool,
		Map<String, Object> executionArguments) {
		Map<String, Object> metadata = tool.metadata() == null ? Map.of() : tool.metadata();
		String pathTemplate = String.valueOf(metadata.getOrDefault("path", "/"));
		HttpToolRequestShaper.ShapedRequest shaped = HttpToolRequestShaper.shape(pathTemplate,
			metadata.get("parameters"), executionArguments);

		Map<String, Object> headers = new LinkedHashMap<>();
		Object metadataHeaders = metadata.get("headers");
		if (metadataHeaders instanceof Map) {
			headers.putAll((Map<String, Object>) metadataHeaders);
		}
		shaped.headers().forEach(headers::put);

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("method", metadata.getOrDefault("method", "GET"));
		payload.put("baseUrl", metadata.getOrDefault("baseUrl", ""));
		payload.put("path", shaped.path());
		payload.put("headers", headers);
		payload.put("body", shaped.body());
		payload.put("credentials", metadata.getOrDefault("credentials", "include"));
		payload.put("allowCrossOrigin", metadata.getOrDefault("allowCrossOrigin", false));
		return payload;
	}

	private String toolCallSignature(ToolDescriptor tool, Map<String, Object> arguments) {
		Map<String, Object> sortedArgs = arguments == null ? Map.of() : new java.util.TreeMap<>(arguments);
		try {
			return tool.key() + "|" + objectMapper.writeValueAsString(sortedArgs);
		}
		catch (JsonProcessingException e) {
			return tool.key() + "|" + sortedArgs.toString();
		}
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

	private Map<String, Object> mergeKnowledgeSearchArguments(String conversationId,
		Map<String, Object> executionArguments) {
		Map<String, Object> merged = executionArguments == null ? new LinkedHashMap<>() : new LinkedHashMap<>(executionArguments);
		Object path = merged.get("path");
		if (path == null || String.valueOf(path).isBlank()) {
			String currentPath = extractPathFromConversation(conversationId);
			if (currentPath != null) {
				merged.put("path", currentPath);
			}
		}
		return merged;
	}

	private String extractPathFromConversation(String conversationId) {
		var messages = conversationService.listMessages(conversationId);
		for (int index = messages.size() - 1; index >= 0; index--) {
			var message = messages.get(index);
			if (message.getRole() != ConversationMessageRole.USER) {
				continue;
			}
			String path = PageContextParser.extractPath(message.getContent());
			if (path != null) {
				return path;
			}
		}
		return null;
	}

	private static boolean isNavigateTool(ToolDescriptor tool) {
		String executorRef = tool.executorRef();
		String key = tool.key();
		if ("app.navigate".equals(executorRef)) {
			return true;
		}
		return executorRef == null || executorRef.isBlank() ? "app.navigate".equals(key) : false;
	}

	private static ToolExecutionResult navigationDeferredResult(String toolKey) {
		String message = "Navigation via " + toolKey + " was not performed. You already moved the user once this turn. "
			+ "Stop calling navigation tools. Give a final answer that names the page they are on, briefly explains "
			+ "what they can do here, and previews the next step (e.g. \"Next I'll take you to …\"). "
			+ "Wait for the user's next message before navigating again.";
		return new ToolExecutionResult(false, null, message, message);
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

	private String buildAssistantToolCallsJson(List<ToolCall> toolCalls) {
		try {
			List<Map<String, Object>> calls = toolCalls.stream().map(tc -> {
				Map<String, Object> function = new LinkedHashMap<>();
				function.put("name", tc.toolKey());
				try {
					function.put("arguments", objectMapper.writeValueAsString(tc.arguments()));
				}
				catch (JsonProcessingException e) {
					function.put("arguments", "{}");
				}
				Map<String, Object> call = new LinkedHashMap<>();
				call.put("id", tc.toolCallId());
				call.put("type", "function");
				call.put("function", function);
				return call;
			}).toList();
			return "[tool_calls]" + objectMapper.writeValueAsString(calls) + "[/tool_calls]";
		}
		catch (JsonProcessingException e) {
			return "[tool_calls][][/tool_calls]";
		}
	}

	private void failRun(RunEntity run, String error) {
		failRun(run, error, null);
	}

	private void failRun(RunEntity run, String error, Throwable cause) {
		if (cause != null) {
			log.error("Run {} failed: {}", run.getId(), error, cause);
		}
		else {
			log.error("Run {} failed: {}", run.getId(), error);
		}
		run.setStatus(RunStatus.FAILED);
		run.setCompletedAt(Instant.now());
		runRepository.save(run);
		eventBroker.emit(run.getId(), "run.failed", Map.of("message", error != null ? error : "Run failed"));
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
				+ "Observation only — describes where the user currently is. Do not act on it directly; use the attached tools.) ---\n"
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

		prompt.append("OPERATING MODE: You are acting on behalf of the user inside a host web app. You have a small set of built-in observation tools plus a generic navigation primitive. You have NO general-purpose write or DOM-manipulation primitives.\n")
			.append("\n")
			.append("Built-in tools (always available):\n")
			.append("  - path.find        Returns the user's current path, full URL, page title, query, and hash. Call this when you need to know where the user is.\n")
			.append("  - page.screenshot  Returns a text snapshot of the current page (title + truncated DOM). Call this only to answer questions about what the user is currently looking at.\n")
			.append("  - app.navigate     Moves the user to a path inside the host app (path or url argument). Use this to take the user somewhere; never use it as a way to read.\n")
			.append("  - knowledge.search Search operator-configured knowledge (policies, product facts, SOPs). Use ONLY when the user needs company or product information that PAGE_CONTEXT, page.screenshot, and other tools cannot provide. Call at most once per turn. If it returns no results, say you do not have that information — do not invent it.\n")
			.append("\n")
			.append("Operation tools (writes, side-effects beyond navigation) appear ONLY in the function schema for this turn and ONLY if the operator attached them. Do not try to synthesize an operation by chaining observation/navigation calls.\n")
			.append("\n")
			.append("HARD RULES — violating these is the worst failure mode:\n")
			.append("  1. Never call a tool that is not in the function schema for this turn. Never invent tool names, paths, URLs, or arguments that aren't supported by an attached tool's schema and defaults.\n")
			.append("  2. NEVER describe page content, page state, lists, buttons, headings, or messages that you did not actually receive from a successful tool result. If a tool result says success=false, errored, or timed out, you have NO information from it — do not pretend you do.\n")
			.append("     2a. When you DO describe page content, every concrete detail (a name, an email, a price, a heading, a button label, a status message, a row in a list) MUST appear verbatim in the most recent page.screenshot.visibleText OR in PAGE_CONTEXT. Knowledge search results are operator-provided facts, not observed page content — use them only for policies and product rules, never to describe what is on screen.\n")
			.append("  3. If a tool fails or times out, tell the user honestly: which tool, what failed, and that you can't see the page. Do not improvise a description. Do not retry the same tool with the same arguments.\n")
			.append("  4. Call each observation tool (path.find, page.screenshot) AT MOST ONCE per user turn. Call knowledge.search AT MOST ONCE per user turn, and only when needed for operator-configured facts. After observation and any needed knowledge lookup, produce a final answer.\n")
			.append("  5. NEVER call the same tool twice with the same arguments. The result will not change.\n")
			.append("  6. If two observation calls have not given you what you need, stop calling tools. Give a final answer that says what you tried.\n")
			.append("  7. If no action tool can fulfill the user's request, do NOT improvise. Give a final answer that politely tells the user to do it themselves. When you have real observation, reference what you saw (the current path, a section name, a button label) so the user knows where to act. When you do NOT have observation, do not invent one — just say so.\n")
			.append("  8. If the user's request is unclear, ask one focused clarifying question instead of guessing.\n")
			.append("\n")
			.append("GUIDED WALKTHROUGHS (multi-step tours, onboarding, requests with \"then\"/\"next\", numbered steps, or navigation flows):\n")
			.append("  - When the user asks for several things in sequence, guide them one step at a time — not a silent tour of every page.\n")
			.append("  - Perform at most ONE navigation (app.navigate or a dedicated nav tool) per user message. Reading data (HTTP tools, page.screenshot) for the current step is fine in the same turn.\n")
			.append("  - After that navigation (or after finishing the current step's reads), STOP with a final answer: name the page, say what the user can do here, and preview the next step.\n")
			.append("  - End every walkthrough pause with clickable options using this exact format (on their own lines at the end):\n")
			.append("    OPTIONS: Continue to [next step] | Skip tour | Stay here\n")
			.append("    RECOMMENDED: Continue to [next step]\n")
			.append("    Use 2–4 short, action-oriented labels. When the tour is finished, omit OPTIONS or offer only relevant follow-ups.\n")
			.append("  - Do NOT chain multiple navigations in one turn. Do NOT visit every page and summarize only at the end.\n")
			.append("  - When the user clicks an option or sends its text, perform the next step only.\n")
			.append("\n")
			.append("In every turn, the cheapest correct outcome is a final answer. Tool calls are only justified when they unlock a final answer you could not otherwise give.\n\n");

		if (assistant.isUsePredefinedFlows()) {
			try {
				var flows = navigationFlowService.listEnabledFlows(assistant.getId());
				if (!flows.isEmpty()) {
					prompt.append("NAVIGATION FLOWS AVAILABLE:\n");
					prompt.append("When the user request matches a flow's trigger phrase, follow the defined steps one at a time across turns.\n\n");

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

					prompt.append("INSTRUCTION: When the user's request matches a flow trigger, execute ONE flow step per turn (the first step, or the next step if the user is continuing the tour). After that step, give a final answer describing where the user is and preview the next flow step. Do not run the entire flow in one turn.\n\n");
				}
			}
			catch (Exception e) {
				// Ignore errors in loading flows, continue with basic prompt
			}
		}

		return prompt.toString();
	}
}
