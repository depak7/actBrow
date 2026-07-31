package com.actbrow.actbrow.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

import com.actbrow.actbrow.agent.FinalResponseDecision;
import com.actbrow.actbrow.agent.ModelDecision;
import com.actbrow.actbrow.agent.ToolCall;
import com.actbrow.actbrow.agent.ToolCallDecision;
import com.actbrow.actbrow.agent.ToolDescriptor;
import com.actbrow.actbrow.agent.ToolExecutionResult;
import com.actbrow.actbrow.api.NotFoundException;
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
import com.actbrow.actbrow.model.RunPhase;
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
	private final RunEventBroker eventBroker;
	private final PendingClientToolStore pendingClientToolStore;
	private final NavigationFlowService navigationFlowService;
	private final RunMemoryService runMemoryService;
	private final RunPlanner runPlanner;
	private final RunExecutor runExecutor;
	private final RunVerifier runVerifier;
	private final RunPolicyEngine runPolicyEngine;
	private final RunCheckpointService runCheckpointService;
	private final EvalTraceRecorder evalTraceRecorder;
	private final FeatureFlagService featureFlagService;
	private final ToolCircuitBreaker toolCircuitBreaker;
	private final AuditLogService auditLogService;
	private final ProgressiveToolDisclosureService progressiveToolDisclosureService;
	private final ActbrowProperties properties;
	private static final String PROMPT_VERSION = "v1";
	private final ObjectMapper objectMapper;
	private final String defaultChatModel;
	// Fast in-process cancel signal only; cross-instance cancellation is observed via run status in the DB.
	private final Set<String> cancelledRuns = ConcurrentHashMap.newKeySet();

	public RunService(RunRepository runRepository, RunStepRepository runStepRepository,
		ConversationService conversationService, AssistantService assistantService, ToolService toolService,
		RunEventBroker eventBroker, PendingClientToolStore pendingClientToolStore,
		NavigationFlowService navigationFlowService, KnowledgeSearchToolExecutor knowledgeSearchToolExecutor,
		RunMemoryService runMemoryService,
		RunPlanner runPlanner, RunExecutor runExecutor, RunVerifier runVerifier,
		RunPolicyEngine runPolicyEngine, RunCheckpointService runCheckpointService,
		EvalTraceRecorder evalTraceRecorder, FeatureFlagService featureFlagService,
		ToolCircuitBreaker toolCircuitBreaker, AuditLogService auditLogService,
		ProgressiveToolDisclosureService progressiveToolDisclosureService,
		ActbrowProperties properties, ObjectMapper objectMapper,
		@Value("${spring.ai.openai.chat.options.model:gemini-2.5-flash}") String defaultChatModel) {
		this.runRepository = runRepository;
		this.runStepRepository = runStepRepository;
		this.conversationService = conversationService;
		this.assistantService = assistantService;
		this.toolService = toolService;
		this.eventBroker = eventBroker;
		this.pendingClientToolStore = pendingClientToolStore;
		this.navigationFlowService = navigationFlowService;
		this.runMemoryService = runMemoryService;
		this.runPlanner = runPlanner;
		this.runExecutor = runExecutor;
		this.runVerifier = runVerifier;
		this.runPolicyEngine = runPolicyEngine;
		this.runCheckpointService = runCheckpointService;
		this.evalTraceRecorder = evalTraceRecorder;
		this.featureFlagService = featureFlagService;
		this.toolCircuitBreaker = toolCircuitBreaker;
		this.auditLogService = auditLogService;
		this.progressiveToolDisclosureService = progressiveToolDisclosureService;
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
		runMemoryService.initializeForRun(saved, userContent);
		// Execution starts immediately — it must not depend on a client opening the SSE stream.
		ensureRunStarted(saved.getId());
		return toResponse(saved);
	}

	/**
	 * Idempotent, multi-instance-safe run start. Ownership is a single atomic UPDATE in the database
	 * (PENDING → IN_PROGRESS with a heartbeat), so concurrent callers — the create path, the SSE
	 * endpoint, the recovery poller, or another instance — race safely: exactly one wins.
	 *
	 * An in-flight run whose owner stopped heartbeating is resumed only when its checkpoint proves it
	 * was interrupted while PLANNING (no tool dispatch in flight). Interruption mid-EXECUTING may mean
	 * a side effect already happened; re-running it could double a write, so the run fails honestly.
	 */
	public void ensureRunStarted(String runId) {
		RunEntity run = requireRun(runId);
		RunStatus status = run.getStatus();
		if (status == RunStatus.COMPLETED || status == RunStatus.FAILED || status == RunStatus.CANCELLED) {
			return;
		}
		Instant now = Instant.now();
		Instant staleBefore = now.minus(staleClaimWindow());
		if (status != RunStatus.PENDING) {
			if (run.getClaimedAt() != null && !run.getClaimedAt().isBefore(staleBefore)) {
				// A live worker owns this run — nothing to do.
				return;
			}
			boolean writeSafeToResume = runCheckpointService.find(runId)
				.map(checkpoint -> checkpoint.getPhase() == RunPhase.PLANNING)
				.orElse(false);
			if (!writeSafeToResume) {
				// Claim the orphan so only one instance settles it, then fail it honestly.
				if (runRepository.claimForExecution(runId, now, staleBefore) == 1) {
					failRun(run, "The assistant was interrupted while performing an action "
						+ "and could not safely resume. Please try again.");
				}
				return;
			}
		}
		if (runRepository.claimForExecution(runId, now, staleBefore) == 0) {
			return;
		}
		Thread.startVirtualThread(() -> processRun(runId));
	}

	/**
	 * Recovers orphaned runs: PENDING rows whose creator died before claiming them, and in-flight
	 * runs whose worker stopped heartbeating. {@link #ensureRunStarted} applies the write-safety rule.
	 */
	@org.springframework.scheduling.annotation.Scheduled(initialDelay = 30_000, fixedDelay = 30_000)
	public void recoverOrphanedRuns() {
		Instant now = Instant.now();
		List<RunEntity> orphans = new java.util.ArrayList<>();
		orphans.addAll(runRepository.findByStatusAndCreatedAtBefore(RunStatus.PENDING, now.minusSeconds(15)));
		orphans.addAll(runRepository.findByStatusInAndClaimedAtBefore(
			List.of(RunStatus.IN_PROGRESS, RunStatus.WAITING_FOR_CLIENT_TOOL), now.minus(staleClaimWindow())));
		for (RunEntity orphan : orphans) {
			try {
				ensureRunStarted(orphan.getId());
			}
			catch (Exception exception) {
				log.warn("Failed to recover orphaned run {}", orphan.getId(), exception);
			}
		}
	}

	/**
	 * How long a heartbeat may be silent before the run counts as orphaned. Must comfortably exceed
	 * both the client-tool wait (no heartbeat while parked on the pending-tool future) and a slow
	 * model call, otherwise a healthy run could be double-claimed.
	 */
	private java.time.Duration staleClaimWindow() {
		java.time.Duration toolWindow = properties.toolTimeout().plusSeconds(60);
		java.time.Duration floor = java.time.Duration.ofMinutes(5);
		return toolWindow.compareTo(floor) > 0 ? toolWindow : floor;
	}

	@Transactional
	public RunResponse cancelRun(String runId) {
		RunEntity run = requireRun(runId);
		RunStatus status = run.getStatus();
		if (status == RunStatus.COMPLETED || status == RunStatus.FAILED || status == RunStatus.CANCELLED) {
			return toResponse(run);
		}
		cancelledRuns.add(runId);
		// Terminal CAS: only flips the run if it is still active, so a concurrent worker completing
		// the run cannot be overwritten — and once CANCELLED, the worker's own terminal CAS loses.
		boolean won = runRepository.finishIfActive(runId, RunStatus.CANCELLED, "Cancelled by client",
			Instant.now()) == 1;
		// Unblock a run parked in pendingClientToolStore.register(...).get(timeout) so cancellation
		// takes effect immediately instead of waiting out the full tool timeout.
		pendingClientToolStore.cancelByRunId(runId);
		if (won) {
			eventBroker.emit(runId, "run.cancelled", Map.of());
			eventBroker.complete(runId);
		}
		return toResponse(requireRun(runId));
	}

	public RunEntity requireRun(String runId) {
		return runRepository.findById(runId).orElseThrow(() -> new NotFoundException("Run not found"));
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
			eventBroker.dispose(runId);
			runStepRepository.deleteByRunId(runId);
			runMemoryService.deleteByRunId(runId);
			runCheckpointService.clear(runId);
		}
		runRepository.deleteAll(runs);
		conversationService.deleteMessagesAndConversation(conversationId);
		runMemoryService.deleteByConversationId(conversationId);
		for (RunEntity run : runs) {
			cancelledRuns.remove(run.getId());
		}
	}

	public void submitClientToolResult(String runId, String toolCallId, ToolExecutionResult result) {
		requireRun(runId);
		pendingClientToolStore.complete(runId, toolCallId, result);
	}

	private boolean isCancelled(String runId) {
		if (cancelledRuns.contains(runId)) {
			return true;
		}
		return runRepository.findById(runId)
			.map(run -> run.getStatus() == RunStatus.CANCELLED)
			.orElse(true);
	}

	private void processRun(String runId) {
		RunEntity run = requireRun(runId);

		if (isCancelled(runId)) {
			cancelRunInternal(run);
			return;
		}

		eventBroker.emit(runId, "run.started", Map.of(
			"assistantId", run.getAssistantId(),
			"conversationId", run.getConversationId()));
		// Status is already IN_PROGRESS: the claim in ensureRunStarted performed that transition
		// atomically. No blind entity save here — it could overwrite a concurrent cancellation.

		try {
			AssistantDefinitionEntity assistant = assistantService.requireEntity(run.getAssistantId());
			List<ToolDescriptor> catalog = toolService.listDescriptorsForAssistant(assistant.getId());
			RunToolFailureTracker failureTracker = new RunToolFailureTracker(objectMapper, properties.maxToolRetries());
			boolean navigatedThisRun = false;
			// Deterministic policy escalation: once set, the next planner turn runs with no tools so
			// the model must produce an honest final answer instead of continuing to act.
			boolean forceFinalAnswer = false;
			String forceFinalReason = null;
			evalTraceRecorder.begin(run, PROMPT_VERSION, toolsetVersion(catalog));

			// The assistant's configured model wins; fall back to the deployment default.
			String chatModel = assistant.getModel();
			if (chatModel == null || chatModel.isBlank()) {
				chatModel = model;
			}
			if (chatModel == null || chatModel.isBlank()) {
				chatModel = defaultChatModel;
			}

			for (int stepIndex = 0; stepIndex < properties.maxSteps(); stepIndex++) {
				if (isCancelled(runId)) {
					cancelRunInternal(run);
					return;
				}

				// Heartbeat + step counter in one guarded update; zero rows means the run was
				// cancelled or deleted out from under us — stop instead of overwriting that state.
				if (runRepository.recordProgress(runId, stepIndex + 1, Instant.now()) == 0) {
					cancelRunInternal(run);
					return;
				}
				runCheckpointService.recordPhase(runId, run.getConversationId(), RunPhase.PLANNING, stepIndex);

				List<com.actbrow.actbrow.model.ConversationMessageEntity> messages = conversationService
					.listMessages(run.getConversationId());
				List<ToolDescriptor> tools = forceFinalAnswer
					? List.of()
					: progressiveToolDisclosureService.selectForPlanning(run, catalog);
				String runtimeGuidance = failureTracker.buildRuntimeGuidance();
				if (forceFinalAnswer) {
					runtimeGuidance += "POLICY DECISION (deterministic, non-negotiable): tool execution for this "
						+ "run has been stopped — " + forceFinalReason + " No tools are available this turn. "
						+ "Produce an honest final answer: state plainly what was attempted, what failed or is "
						+ "blocked, and what the user can do next. Do not invent results.\n\n";
				}
				// Stream text tokens to the client as they are generated. Deltas are advisory UI
				// output: the authoritative content is still the decision returned below, and a
				// cancelled run stops emitting immediately.
				final int deltaStepIndex = stepIndex;
				java.util.concurrent.atomic.AtomicInteger deltaSequence = new java.util.concurrent.atomic.AtomicInteger();
				java.util.function.Consumer<String> onTextDelta = delta -> {
					if (isCancelled(runId)) {
						return;
					}
					Map<String, Object> deltaPayload = new LinkedHashMap<>();
					deltaPayload.put("stepIndex", deltaStepIndex);
					deltaPayload.put("sequence", deltaSequence.getAndIncrement());
					deltaPayload.put("delta", delta);
					eventBroker.emit(runId, "assistant.message.delta", deltaPayload);
				};
				RunPlanner.PlanningOutcome planning = runPlanner.plan(chatModel, assistant, run, messages, tools,
					stepIndex, buildSystemPrompt(assistant, run.getConversationId()),
					runtimeGuidance, onTextDelta);
				ModelDecision decision = planning.decision();
				recordStep(runId, stepIndex, RunStepType.MODEL_DECISION, decision.toString());
				runMemoryService.recordModelDecision(run, decision, stepIndex);
				evalTraceRecorder.recordPlanning(runId, decision.toString());

				if (decision instanceof FinalResponseDecision finalResponse) {
					// Win the terminal transition BEFORE publishing the answer: if a concurrent
					// cancel already flipped the run, the cancellation is final and we stay silent.
					if (runRepository.finishIfActive(runId, RunStatus.COMPLETED, null, Instant.now()) == 0) {
						cancelRunInternal(run);
						return;
					}
					conversationService.appendMessage(run.getConversationId(), ConversationMessageRole.ASSISTANT,
						finalResponse.message());
					recordStep(runId, stepIndex, RunStepType.FINAL_RESPONSE, finalResponse.message());
					runMemoryService.recordFinalResponse(run, finalResponse.message());
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
					eventBroker.complete(runId);
					runCheckpointService.clear(runId);
					evalTraceRecorder.finalizeTrace(runId, "COMPLETED", latencyMs(run));
					return;
				}

				ToolCallDecision toolCallDecision = (ToolCallDecision) decision;

				// Store the ASSISTANT tool-calls message so providers can reconstruct valid API history
				conversationService.appendMessage(run.getConversationId(), ConversationMessageRole.ASSISTANT,
					buildAssistantToolCallsJson(toolCallDecision.toolCalls()));

				// Execute each tool call sequentially within this step
				runCheckpointService.recordPhase(runId, run.getConversationId(), RunPhase.EXECUTING, stepIndex);
				String assistantId = assistant.getId();
				for (ToolCall toolCall : toolCallDecision.toolCalls()) {
					if (isCancelled(runId)) {
						cancelRunInternal(run);
						return;
					}
					ToolDescriptor tool = resolveTool(tools, catalog, toolCall);
					Map<String, Object> executionArguments = mergeArguments(tool, toolCall.arguments());
					String executorKey = resolveExecutorKey(tool);
					boolean deferNavigation = navigatedThisRun && isNavigateTool(tool);
					ToolContract contract = ToolContract.from(tool);

					ToolExecutionResult result;
					if (deferNavigation) {
						recordStep(runId, stepIndex, RunStepType.TOOL_CALL, toolCall.toString() + " [deferred]");
						result = navigationDeferredResult(tool.key());
					}
					else {
						// Production controls (Phase 7): kill switch, circuit breaker, shadow mode.
						auditLogService.toolAttempt(runId, assistantId, tool.key());
						String blockReason = null;
						if (!featureFlagService.isEnabled(assistantId, FeatureFlagService.TOOLS_ENABLED)) {
							blockReason = "tool execution is disabled for this assistant";
						}
						// Circuits are scoped per assistant so one tenant's failing tool can never
						// open the circuit for every other tenant sharing the same tool key.
						else if (!toolCircuitBreaker.allow(circuitKey(assistantId, tool))) {
							auditLogService.circuitOpen(runId, assistantId, tool.key());
							blockReason = "tool circuit is open after repeated failures";
						}
						boolean shadow = blockReason == null && contract.isWrite()
							&& featureFlagService.isEnabled(assistantId, FeatureFlagService.SHADOW_MODE);

						if (blockReason != null) {
							recordStep(runId, stepIndex, RunStepType.TOOL_CALL,
								toolCall.toString() + " [blocked: " + blockReason + "]");
							result = syntheticBlocked(tool.key(), blockReason);
						}
						else if (shadow) {
							auditLogService.shadowSkip(runId, assistantId, tool.key());
							recordStep(runId, stepIndex, RunStepType.TOOL_CALL, toolCall.toString() + " [shadow]");
							result = syntheticShadow(tool.key());
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

							RunExecutor.ExecutionOutcome execution = runExecutor.execute(run, toolCall, tool,
								executionArguments, navigatedThisRun, failureTracker);
							result = execution.result();
							if (execution.navigated()) {
								navigatedThisRun = true;
							}
							if (result.success()) {
								toolCircuitBreaker.recordSuccess(circuitKey(assistantId, tool));
							}
							else {
								toolCircuitBreaker.recordFailure(circuitKey(assistantId, tool));
							}
						}
						auditLogService.toolOutcome(runId, assistantId, tool.key(), result.success(), result.error());
						evalTraceRecorder.recordExecutionAttempt(runId);
					}
					failureTracker.recordResult(tool.key(), toolCallSignature(tool, executionArguments), result);
					String toolMessage = toolResultContentForModel(result);
					// Store TOOL message with its toolCallId so providers can pair it with the ASSISTANT entry
					conversationService.appendMessage(run.getConversationId(), ConversationMessageRole.TOOL, toolMessage,
						toolCall.toolCallId());
					runMemoryService.recordToolResult(run, toolCall, tool, executionArguments, result, stepIndex);
					recordStep(runId, stepIndex, RunStepType.TOOL_RESULT, result.toString());
					Map<String, Object> payload = new LinkedHashMap<>();
					payload.put("toolCallId", toolCall.toolCallId());
					payload.put("success", result.success());
					payload.put("structuredOutput", result.structuredOutput());
					payload.put("textSummary", result.textSummary());
					payload.put("error", result.error());
					eventBroker.emit(runId, "tool.call.completed", payload);
					runCheckpointService.recordPhase(runId, run.getConversationId(), RunPhase.VERIFYING, stepIndex);
					RunVerifier.VerificationDecision verification = runVerifier.verify(tool, result);
					recordStep(runId, stepIndex, RunStepType.VERIFIER_DECISION, verification.toString());
					evalTraceRecorder.recordVerifier(runId, verification.toString());
					// Deterministic recovery policy from the structured failure type. Recorded in the
					// trace so recovery is code-driven and auditable; the planner still drives the next turn.
					RunPolicyEngine.PolicyDecision policy = runPolicyEngine.decide(
						verification.failureType(), tools.size() > 1);
					recordStep(runId, stepIndex, RunStepType.POLICY_DECISION, policy.toString());
					// The policy decision is control flow, not just a trace entry: terminal outcomes
					// strip the toolset for the next planner turn, forcing an honest final answer.
					if (policy.action() == RunPolicyEngine.PolicyAction.STOP_WITH_EXPLANATION
						|| policy.action() == RunPolicyEngine.PolicyAction.REQUIRE_USER_INTERVENTION) {
						forceFinalAnswer = true;
						forceFinalReason = policy.rationale();
					}
					// Safer tool contracts (Phase 6): a successful write should be verified after the fact.
					if (contract.requiresPostVerification() && result.success()) {
						recordStep(runId, stepIndex, RunStepType.POLICY_DECISION,
							"write tool '" + tool.key() + "' (sideEffect=" + contract.sideEffectLevel()
								+ ") requires post-action verification");
					}
					if (verification.yieldToPlanner()) {
						break;
					}
				}
			}

			failRun(run, "Run exceeded max steps");
		}
		catch (java.util.concurrent.CancellationException cancellation) {
			// Run was cancelled externally (e.g. conversation deleted while a client tool was pending).
			// The conversation and run rows are already gone — do not attempt any further DB writes.
		}
		catch (Exception exception) {
			if (isCancelled(runId)) {
				// Exception surfaced from a cancelled run — swallow to avoid writing to a deleted row.
				return;
			}
			// Log full detail server-side, but never surface raw internal errors (SQL, stack traces)
			// to the client — the user-facing message stays generic.
			failRun(run, "The assistant hit an unexpected error while processing this request.", exception);
		}
		finally {
			cancelledRuns.remove(runId);
		}
	}

	private void cancelRunInternal(RunEntity run) {
		runCheckpointService.clear(run.getId());
		evalTraceRecorder.finalizeTrace(run.getId(), "CANCELLED", latencyMs(run));
		// If the run row was deleted mid-flight (e.g. conversation deleted), do not re-insert it.
		if (!runRepository.existsById(run.getId())) {
			return;
		}
		// Terminal CAS: a no-op when another party already finished the run (their outcome stands).
		if (runRepository.finishIfActive(run.getId(), RunStatus.CANCELLED, "Cancelled by client",
			Instant.now()) == 1) {
			runMemoryService.recordRunFailure(run.getId(), "Run cancelled");
			eventBroker.emit(run.getId(), "run.cancelled", Map.of());
		}
		eventBroker.complete(run.getId());
	}

	/** A stable version marker for the current tool catalog, recorded on eval traces. */
	private static ToolDescriptor resolveTool(List<ToolDescriptor> active, List<ToolDescriptor> catalog,
		ToolCall toolCall) {
		return active.stream()
			.filter(item -> matchesTool(item, toolCall))
			.findFirst()
			.or(() -> catalog.stream().filter(item -> matchesTool(item, toolCall)).findFirst())
			.or(() -> ProgressiveToolDisclosureService.metaToolByKey(toolCall.toolKey()))
			.orElseThrow(() -> new IllegalArgumentException(
				"Unknown tool requested by model: " + toolCall.toolKey()));
	}

	private static boolean matchesTool(ToolDescriptor item, ToolCall toolCall) {
		if (toolCall.toolKey() != null && toolCall.toolKey().equals(item.key())) {
			return true;
		}
		return item.id() != null && item.id().equals(toolCall.toolId());
	}

	private static String toolsetVersion(List<ToolDescriptor> tools) {
		List<String> keys = tools.stream().map(ToolDescriptor::key).sorted().toList();
		return "t" + Integer.toHexString(keys.hashCode());
	}

	private static long latencyMs(RunEntity run) {
		if (run.getCreatedAt() == null) {
			return 0L;
		}
		return Math.max(0L, System.currentTimeMillis() - run.getCreatedAt().toEpochMilli());
	}

	/** Result used when a tool is blocked by a production control (kill switch / open circuit). */
	private static ToolExecutionResult syntheticBlocked(String toolKey, String reason) {
		String message = "Tool '" + toolKey + "' was not run: " + reason
			+ ". Choose a different tool or produce a final answer explaining the block.";
		return new ToolExecutionResult(false, null, message, message);
	}

	/** Result used when shadow (observe-only) mode suppresses a write tool's execution. */
	private static ToolExecutionResult syntheticShadow(String toolKey) {
		String message = "Shadow mode: write tool '" + toolKey
			+ "' was observed but not executed. Treat as if the action was recorded for review.";
		return new ToolExecutionResult(true, null, message, null);
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
		String method = String.valueOf(metadata.getOrDefault("method", "GET"));
		HttpToolRequestShaper.ShapedRequest shaped = HttpToolRequestShaper.shape(method, pathTemplate,
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
		return tool.key() + "|" + sortedArgs;
	}

	/** Circuit-breaker scope: per assistant + tool, so failures never bleed across tenants. */
	private static String circuitKey(String assistantId, ToolDescriptor tool) {
		return assistantId + "|" + tool.key();
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

	private static ToolExecutionResult navigationDeferredResult(String toolKey) {
		String message = "Navigation via " + toolKey + " was not performed. You already moved the user once this turn. "
			+ "Stop calling navigation tools. Give a final answer that names the page they are on, briefly explains "
			+ "what they can do here, and previews the next step (e.g. \"Next I'll take you to …\"). "
			+ "Wait for the user's next message before navigating again.";
		return new ToolExecutionResult(false, null, message, message);
	}

	private static boolean isNavigateTool(ToolDescriptor tool) {
		String executorRef = tool.executorRef();
		String key = tool.key();
		if ("app.navigate".equals(executorRef)) {
			return true;
		}
		return executorRef == null || executorRef.isBlank() ? "app.navigate".equals(key) : false;
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
		runCheckpointService.clear(run.getId());
		evalTraceRecorder.finalizeTrace(run.getId(), "FAILED", latencyMs(run));
		// Terminal CAS: no-ops when the run row is gone or another party already finished the run,
		// so a failure can never overwrite a completion or cancellation.
		if (runRepository.finishIfActive(run.getId(), RunStatus.FAILED,
			error != null ? error : "Run failed", Instant.now()) == 0) {
			return;
		}
		runMemoryService.recordRunFailure(run.getId(), error != null ? error : "Run failed");
		eventBroker.emit(run.getId(), "run.failed", Map.of("message", error != null ? error : "Run failed"));
		eventBroker.complete(run.getId());
	}

	private void recordStep(String runId, int stepIndex, RunStepType type, String payload) {
		// Trace steps are auxiliary — a failure to persist one must never abort the run or surface
		// to the user. Log and continue.
		try {
			RunStepEntity step = new RunStepEntity();
			step.setRunId(runId);
			step.setStepIndex(stepIndex);
			step.setType(type);
			step.setPayload(payload);
			runStepRepository.save(step);
		}
		catch (Exception exception) {
			log.warn("Failed to record run step {} for run {} (non-fatal)", type, runId, exception);
		}
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
			.append("     3a. Tool failures do NOT automatically end the run. If the failure suggests a repair, continue with a corrected next action, a different tool, or one focused clarification.\n")
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
