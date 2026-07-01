package com.actbrow.actbrow.service;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.actbrow.actbrow.agent.ToolCall;
import com.actbrow.actbrow.agent.ToolDescriptor;
import com.actbrow.actbrow.agent.ToolExecutionResult;
import com.actbrow.actbrow.config.ActbrowProperties;
import com.actbrow.actbrow.model.RunEntity;
import com.actbrow.actbrow.model.RunStatus;
import com.actbrow.actbrow.repository.RunRepository;

@Service
public class RunExecutor {

	private static final Logger log = LoggerFactory.getLogger(RunExecutor.class);

	private final PendingClientToolStore pendingClientToolStore;
	private final BuiltinServerToolExecutor builtinServerToolExecutor;
	private final HttpServerToolExecutor httpServerToolExecutor;
	private final KnowledgeSearchToolExecutor knowledgeSearchToolExecutor;
	private final ConversationService conversationService;
	private final RunRepository runRepository;
	private final ActbrowProperties properties;

	public RunExecutor(PendingClientToolStore pendingClientToolStore,
		BuiltinServerToolExecutor builtinServerToolExecutor,
		HttpServerToolExecutor httpServerToolExecutor,
		KnowledgeSearchToolExecutor knowledgeSearchToolExecutor,
		ConversationService conversationService,
		RunRepository runRepository,
		ActbrowProperties properties) {
		this.pendingClientToolStore = pendingClientToolStore;
		this.builtinServerToolExecutor = builtinServerToolExecutor;
		this.httpServerToolExecutor = httpServerToolExecutor;
		this.knowledgeSearchToolExecutor = knowledgeSearchToolExecutor;
		this.conversationService = conversationService;
		this.runRepository = runRepository;
		this.properties = properties;
	}

	public ExecutionOutcome execute(RunEntity run, ToolCall toolCall, ToolDescriptor tool,
		Map<String, Object> executionArguments, boolean navigationAlreadyPerformed,
		RunToolFailureTracker failureTracker) throws Exception {
		if (navigationAlreadyPerformed && isNavigateTool(tool)) {
			return new ExecutionOutcome(navigationDeferredResult(tool.key()), false, true);
		}
		String signature = toolCallSignature(tool, executionArguments);
		RunToolFailureTracker.GuardDecision guard = failureTracker.beforeToolCall(tool.key(), signature);
		if (!guard.allowed()) {
			return new ExecutionOutcome(guard.syntheticResult(), false, false);
		}
		try {
			ToolExecutionResult result = executeInternal(run, toolCall, tool, executionArguments);
			boolean navigated = result.success() && isNavigateTool(tool);
			return new ExecutionOutcome(result, navigated, false);
		}
		catch (Exception exception) {
			log.warn("Tool {} failed in run {} with arguments {}", tool.key(), run.getId(), executionArguments, exception);
			run.setStatus(RunStatus.IN_PROGRESS);
			runRepository.save(run);
			return new ExecutionOutcome(failureTracker.executorFailureResult(tool.key(), exception), false, false);
		}
	}

	private ToolExecutionResult executeInternal(RunEntity run, ToolCall toolCall, ToolDescriptor tool,
		Map<String, Object> executionArguments) throws Exception {
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

	private Map<String, Object> mergeKnowledgeSearchArguments(String conversationId,
		Map<String, Object> executionArguments) {
		Map<String, Object> merged = executionArguments == null
			? new java.util.LinkedHashMap<>()
			: new java.util.LinkedHashMap<>(executionArguments);
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
			if (message.getRole() != com.actbrow.actbrow.model.ConversationMessageRole.USER) {
				continue;
			}
			String path = com.actbrow.actbrow.conversation.PageContextParser.extractPath(message.getContent());
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

	private static String toolCallSignature(ToolDescriptor tool, Map<String, Object> arguments) {
		Map<String, Object> sortedArgs = arguments == null ? Map.of() : new java.util.TreeMap<>(arguments);
		return tool.key() + "|" + sortedArgs;
	}

	public record ExecutionOutcome(
		ToolExecutionResult result,
		boolean navigated,
		boolean deferred
	) {
	}
}
