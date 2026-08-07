package com.actbrow.actbrow.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
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
	/** Cap concurrent server/API tool calls when the model emits a multi-tool step. */
	private final int maxParallelTools;
	private final boolean parallelToolCallsEnabled;
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
		@Value("${spring.ai.openai.chat.options.model:deepseek/deepseek-v4-flash}") String defaultChatModel,
		@Value("${actbrow.agent.max-parallel-tools:8}") int maxParallelTools,
		@Value("${actbrow.agent.parallel-tool-calls:true}") boolean parallelToolCallsEnabled) {
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
		this.maxParallelTools = Math.max(1, maxParallelTools);
		this.parallelToolCallsEnabled = parallelToolCallsEnabled;
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
		orphans.addAll(runRepository.findOrphanedInFlight(
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

	/**
	 * In-memory-only cancellation check, for hot paths that run many times per step (token deltas).
	 * Deliberately skips the database read in {@link #isCancelled}: emitting a few extra cosmetic
	 * deltas after a cancel is harmless, whereas one query per streamed token is not — a 400-token
	 * answer would otherwise issue 400 identical SELECTs on the run's virtual thread.
	 */
	private boolean isCancelledFast(String runId) {
		return cancelledRuns.contains(runId);
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
				// One run_memories read per step, shared by tool disclosure and the context assembler.
				RunMemoryService.RunMemorySnapshot memory = runMemoryService.getSnapshot(runId);
				List<ToolDescriptor> tools = forceFinalAnswer
					? List.of()
					: progressiveToolDisclosureService.selectForPlanning(run, catalog, memory);
				String runtimeGuidance = progressiveToolDisclosureService.specialistRuntimeGuidance(memory)
					+ failureTracker.buildRuntimeGuidance();
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
					// Fast path only: this runs once per token, so it must not touch the database.
					if (isCancelledFast(runId)) {
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
					runtimeGuidance, onTextDelta, memory);
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

				// Execute tool calls for this step. Independent server/API tools run in parallel on
				// virtual threads; browser/client tools stay sequential (they park the run on the SDK).
				runCheckpointService.recordPhase(runId, run.getConversationId(), RunPhase.EXECUTING, stepIndex);
				String assistantId = assistant.getId();
				ToolBatchOutcome batchOutcome = executeToolCallBatch(run, runId, assistantId, stepIndex,
					toolCallDecision.toolCalls(), tools, catalog, failureTracker, navigatedThisRun);
				if (batchOutcome.cancelled()) {
					cancelRunInternal(run);
					return;
				}
				navigatedThisRun = batchOutcome.navigatedThisRun();
				if (batchOutcome.forceFinalAnswer()) {
					forceFinalAnswer = true;
					forceFinalReason = batchOutcome.forceFinalReason();
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

	/**
	 * Runs one model step's tool calls. Parallel-safe server/API tools share a virtual-thread pool
	 * (capped by {@link #maxParallelTools}); client/browser tools always run on the run loop thread
	 * because they flip the run into {@code WAITING_FOR_CLIENT_TOOL}.
	 */
	private ToolBatchOutcome executeToolCallBatch(RunEntity run, String runId, String assistantId, int stepIndex,
		List<ToolCall> toolCalls, List<ToolDescriptor> tools, List<ToolDescriptor> catalog,
		RunToolFailureTracker failureTracker, boolean navigatedThisRun) {
		List<PreparedToolCall> prepared = new ArrayList<>();
		boolean nav = navigatedThisRun;
		for (ToolCall toolCall : toolCalls) {
			if (isCancelled(runId)) {
				return ToolBatchOutcome.cancelled(nav);
			}
			PreparedToolCall item = prepareToolCall(run, runId, assistantId, stepIndex, toolCall, tools, catalog, nav);
			prepared.add(item);
			// Defer further navigates in the same batch once we know one will navigate.
			if (item.willNavigate()) {
				nav = true;
			}
		}

		// Fan-out only when every executable call in the step is server/API-safe. Mixed batches
		// (API + browser) stay sequential so we never run a later API after an earlier browser yield,
		// and TOOL conversation rows keep the model's original call order.
		List<PreparedToolCall> parallelWork = prepared.stream()
			.filter(PreparedToolCall::needsExecutor)
			.filter(PreparedToolCall::parallelSafe)
			.toList();
		boolean batchIsParallelSafe = prepared.stream()
			.filter(PreparedToolCall::needsExecutor)
			.allMatch(PreparedToolCall::parallelSafe);
		boolean useParallel = parallelToolCallsEnabled && batchIsParallelSafe && parallelWork.size() > 1;

		Map<String, ExecutedToolCall> byCallId = new LinkedHashMap<>();
		if (useParallel) {
			for (PreparedToolCall item : parallelWork) {
				emitToolRequested(runId, stepIndex, item);
			}
			executeParallel(run, runId, catalog, failureTracker, parallelWork, byCallId);
		}

		boolean forceFinal = false;
		String forceReason = null;
		for (PreparedToolCall item : prepared) {
			if (isCancelled(runId)) {
				return ToolBatchOutcome.cancelled(nav);
			}
			ExecutedToolCall executed;
			if (!item.needsExecutor()) {
				executed = new ExecutedToolCall(item, item.earlyResult(), false, 0L);
			}
			else if (useParallel) {
				executed = byCallId.get(item.toolCall().toolCallId());
				if (executed == null) {
					executed = runPreparedOnCaller(run, runId, catalog, failureTracker, item);
				}
			}
			else {
				emitToolRequested(runId, stepIndex, item);
				executed = runPreparedOnCaller(run, runId, catalog, failureTracker, item);
			}
			if (executed.navigated()) {
				nav = true;
			}
			PostRecord post = recordToolOutcome(run, runId, assistantId, stepIndex, tools, executed, failureTracker);
			if (post.forceFinalAnswer()) {
				forceFinal = true;
				forceReason = post.forceFinalReason();
			}
			// In a pure parallel API batch, always surface every result (do not break mid-fan-out).
			if (post.yieldToPlanner() && !useParallel) {
				break;
			}
		}
		return new ToolBatchOutcome(false, nav, forceFinal, forceReason);
	}

	private void executeParallel(RunEntity run, String runId, List<ToolDescriptor> catalog,
		RunToolFailureTracker failureTracker, List<PreparedToolCall> parallelWork,
		Map<String, ExecutedToolCall> byCallId) {
		Semaphore permits = new Semaphore(maxParallelTools);
		try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
			List<CompletableFuture<ExecutedToolCall>> futures = new ArrayList<>();
			for (PreparedToolCall item : parallelWork) {
				futures.add(CompletableFuture.supplyAsync(() -> {
					permits.acquireUninterruptibly();
					try {
						if (isCancelled(runId)) {
							return new ExecutedToolCall(item, syntheticBlocked(item.tool().key(), "run cancelled"),
								false, 0L);
						}
						return runPreparedOnCaller(run, runId, catalog, failureTracker, item);
					}
					finally {
						permits.release();
					}
				}, pool));
			}
			for (CompletableFuture<ExecutedToolCall> future : futures) {
				ExecutedToolCall executed = future.join();
				byCallId.put(executed.prepared().toolCall().toolCallId(), executed);
			}
		}
	}

	private PreparedToolCall prepareToolCall(RunEntity run, String runId, String assistantId, int stepIndex,
		ToolCall toolCall, List<ToolDescriptor> tools, List<ToolDescriptor> catalog, boolean navigatedThisRun) {
		Optional<ToolDescriptor> resolved = resolveTool(tools, catalog, toolCall);
		if (resolved.isEmpty()) {
			String requestedKey = toolCall.toolKey() == null || toolCall.toolKey().isBlank()
				? "unknown"
				: toolCall.toolKey().trim();
			ToolDescriptor phantom = phantomUnknownTool(requestedKey);
			ToolExecutionResult result = syntheticUnknownTool(requestedKey, tools);
			recordStep(runId, stepIndex, RunStepType.TOOL_CALL, toolCall.toString() + " [unknown]");
			Map<String, Object> args = toolCall.arguments() == null ? Map.of() : toolCall.arguments();
			return PreparedToolCall.early(toolCall, phantom, args, result, false, false);
		}
		ToolDescriptor tool = resolved.get();
		// Best-in-class harness: if the model called a real catalog tool that belongs to the other
		// specialist (or was not yet activated), auto-route + activate so the call can run and the
		// next planning turn has the correct schema set — no manual agent.use_* required.
		if (!ProgressiveToolDisclosureService.isMetaOrAgentSwitchTool(tool.key())) {
			ProgressiveToolDisclosureService.AutoRouteResult route = progressiveToolDisclosureService
				.routeForToolCall(run, catalog, tool);
			if (route.switched()) {
				recordStep(runId, stepIndex, RunStepType.POLICY_DECISION,
					"harness auto-routed specialist " + route.specialistBefore() + " → "
						+ route.specialistAfter() + " for tool " + tool.key());
			}
		}
		Map<String, Object> executionArguments = mergeArguments(tool, toolCall.arguments());
		ToolContract contract = ToolContract.from(tool);
		boolean deferNavigation = navigatedThisRun && isNavigateTool(tool);
		if (deferNavigation) {
			recordStep(runId, stepIndex, RunStepType.TOOL_CALL, toolCall.toString() + " [deferred]");
			return PreparedToolCall.early(toolCall, tool, executionArguments, navigationDeferredResult(tool.key()),
				false, false);
		}
		String blockReason = null;
		if (!featureFlagService.isEnabled(assistantId, FeatureFlagService.TOOLS_ENABLED)) {
			blockReason = "tool execution is disabled for this assistant";
		}
		else if (!toolCircuitBreaker.allow(circuitKey(assistantId, tool))) {
			auditLogService.circuitOpen(runId, assistantId, tool.key());
			blockReason = "tool circuit is open after repeated failures";
		}
		boolean shadow = blockReason == null && contract.isWrite()
			&& featureFlagService.isEnabled(assistantId, FeatureFlagService.SHADOW_MODE);
		if (blockReason != null) {
			recordStep(runId, stepIndex, RunStepType.TOOL_CALL,
				toolCall.toString() + " [blocked: " + blockReason + "]");
			return PreparedToolCall.early(toolCall, tool, executionArguments,
				syntheticBlocked(tool.key(), blockReason), false, false);
		}
		if (shadow) {
			auditLogService.shadowSkip(runId, assistantId, tool.key());
			recordStep(runId, stepIndex, RunStepType.TOOL_CALL, toolCall.toString() + " [shadow]");
			return PreparedToolCall.early(toolCall, tool, executionArguments, syntheticShadow(tool.key()), false,
				false);
		}
		boolean parallelSafe = ToolCatalogPolicies.isParallelSafe(tool);
		boolean willNavigate = isNavigateTool(tool);
		return PreparedToolCall.executable(toolCall, tool, executionArguments, contract, parallelSafe, willNavigate);
	}

	private void emitToolRequested(String runId, int stepIndex, PreparedToolCall item) {
		Map<String, Object> requestedPayload = new LinkedHashMap<>();
		requestedPayload.put("toolCallId", item.toolCall().toolCallId());
		requestedPayload.put("toolId", item.tool().id());
		requestedPayload.put("toolKey", item.tool().key());
		requestedPayload.put("executorKey", resolveExecutorKey(item.tool()));
		requestedPayload.put("type", wireToolTypeForClients(item.tool()));
		requestedPayload.put("arguments", item.arguments());
		if (ToolCatalogPolicies.executesAsBrowserHttpTool(item.tool())) {
			requestedPayload.put("http", browserHttpPayload(item.tool(), item.arguments()));
		}
		eventBroker.emit(runId, "tool.call.requested", requestedPayload);
		recordStep(runId, stepIndex, RunStepType.TOOL_CALL, item.toolCall().toString()
			+ (item.parallelSafe() ? " [parallel-ok]" : ""));
	}

	private ExecutedToolCall runPreparedOnCaller(RunEntity run, String runId, List<ToolDescriptor> catalog,
		RunToolFailureTracker failureTracker, PreparedToolCall item) {
		if (!item.needsExecutor()) {
			return new ExecutedToolCall(item, item.earlyResult(), false, 0L);
		}
		long started = System.nanoTime();
		try {
			// navigatedThisRun is only meaningful for navigate tools, which never enter the parallel pool.
			RunExecutor.ExecutionOutcome execution = runExecutor.execute(run, item.toolCall(), item.tool(),
				item.arguments(), false, failureTracker, catalog);
			long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
			return new ExecutedToolCall(item, execution.result(), execution.navigated(), elapsedMs);
		}
		catch (java.util.concurrent.CancellationException cancellation) {
			throw cancellation;
		}
		catch (Exception exception) {
			long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
			log.warn("Tool {} failed in batch for run {}", item.tool().key(), runId, exception);
			return new ExecutedToolCall(item, failureTracker.executorFailureResult(item.tool().key(), exception),
				false, elapsedMs);
		}
	}

	private PostRecord recordToolOutcome(RunEntity run, String runId, String assistantId, int stepIndex,
		List<ToolDescriptor> tools, ExecutedToolCall executed, RunToolFailureTracker failureTracker) {
		PreparedToolCall item = executed.prepared();
		ToolDescriptor tool = item.tool();
		ToolCall toolCall = item.toolCall();
		ToolExecutionResult result = executed.result();
		Map<String, Object> executionArguments = item.arguments();

		if (item.needsExecutor()) {
			boolean clientParked = ToolCatalogPolicies.executesAsClientPendingTool(tool.type(), tool.executorRef())
				|| ToolCatalogPolicies.executesAsBrowserHttpTool(tool);
			if ("page.observe".equals(tool.key()) || "page.screenshot".equals(tool.key())) {
				evalTraceRecorder.recordObservationTool(runId, tool.key(),
					clientParked ? executed.elapsedMs() : 0L);
			}
			else if (clientParked) {
				evalTraceRecorder.recordClientToolWait(runId, executed.elapsedMs());
			}
			if (result.success()) {
				toolCircuitBreaker.recordSuccess(circuitKey(assistantId, tool));
			}
			else {
				toolCircuitBreaker.recordFailure(circuitKey(assistantId, tool));
			}
			auditLogService.toolAttempt(runId, assistantId, tool.key());
			auditLogService.toolOutcome(runId, assistantId, tool.key(), result.success(), result.error());
			evalTraceRecorder.recordExecutionAttempt(runId);
		}
		else {
			// early synthetic paths (unknown / blocked / shadow / deferred)
			auditLogService.toolAttempt(runId, assistantId, tool.key());
			auditLogService.toolOutcome(runId, assistantId, tool.key(), result.success(), result.error());
			evalTraceRecorder.recordExecutionAttempt(runId);
		}

		failureTracker.recordResult(tool.key(), toolCallSignature(tool, executionArguments), result);
		String toolMessage = toolResultContentForModel(result);
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
		RunPolicyEngine.PolicyDecision policy = runPolicyEngine.decide(verification.failureType(), tools.size() > 1);
		recordStep(runId, stepIndex, RunStepType.POLICY_DECISION, policy.toString());
		boolean forceFinal = policy.action() == RunPolicyEngine.PolicyAction.STOP_WITH_EXPLANATION
			|| policy.action() == RunPolicyEngine.PolicyAction.REQUIRE_USER_INTERVENTION;
		ToolContract contract = item.contract() != null ? item.contract() : ToolContract.from(tool);
		if (contract.requiresPostVerification() && result.success()) {
			recordStep(runId, stepIndex, RunStepType.POLICY_DECISION,
				"write tool '" + tool.key() + "' (sideEffect=" + contract.sideEffectLevel()
					+ ") requires post-action verification");
		}
		return new PostRecord(forceFinal, forceFinal ? policy.rationale() : null, verification.yieldToPlanner());
	}

	private record ToolBatchOutcome(boolean cancelled, boolean navigatedThisRun, boolean forceFinalAnswer,
		String forceFinalReason) {
		static ToolBatchOutcome cancelled(boolean navigatedThisRun) {
			return new ToolBatchOutcome(true, navigatedThisRun, false, null);
		}
	}

	private record PostRecord(boolean forceFinalAnswer, String forceFinalReason, boolean yieldToPlanner) {
	}

	private record PreparedToolCall(
		ToolCall toolCall,
		ToolDescriptor tool,
		Map<String, Object> arguments,
		ToolContract contract,
		boolean needsExecutor,
		boolean parallelSafe,
		boolean willNavigate,
		ToolExecutionResult earlyResult
	) {
		static PreparedToolCall early(ToolCall toolCall, ToolDescriptor tool, Map<String, Object> arguments,
			ToolExecutionResult result, boolean parallelSafe, boolean willNavigate) {
			return new PreparedToolCall(toolCall, tool, arguments, ToolContract.from(tool), false, parallelSafe,
				willNavigate, result);
		}

		static PreparedToolCall executable(ToolCall toolCall, ToolDescriptor tool, Map<String, Object> arguments,
			ToolContract contract, boolean parallelSafe, boolean willNavigate) {
			return new PreparedToolCall(toolCall, tool, arguments, contract, true, parallelSafe, willNavigate, null);
		}
	}

	private record ExecutedToolCall(
		PreparedToolCall prepared,
		ToolExecutionResult result,
		boolean navigated,
		long elapsedMs
	) {
	}

	/**
	 * Resolve a model tool call against the active schema set, then the full assistant catalog, then
	 * progressive-disclosure meta tools. Soft-matches wire-style names ({@code app_navigate} ↔
	 * {@code app.navigate}) so a catalog tool not in this turn's active schema can still run when the
	 * model echoes a remembered name. Empty when the name was invented and never existed.
	 */
	static Optional<ToolDescriptor> resolveTool(List<ToolDescriptor> active, List<ToolDescriptor> catalog,
		ToolCall toolCall) {
		return active.stream()
			.filter(item -> matchesTool(item, toolCall))
			.findFirst()
			.or(() -> catalog.stream().filter(item -> matchesTool(item, toolCall)).findFirst())
			.or(() -> ProgressiveToolDisclosureService.metaToolByKey(toolCall.toolKey()));
	}

	private static boolean matchesTool(ToolDescriptor item, ToolCall toolCall) {
		if (toolCall.toolId() != null && item.id() != null && toolCall.toolId().equals(item.id())) {
			return true;
		}
		if (toolCall.toolKey() != null && toolCall.toolKey().equals(item.key())) {
			return true;
		}
		// Wire-form equivalence when the provider passed an unresolved name through.
		if (toolCall.toolKey() != null) {
			String requested = normalizeToolWireName(toolCall.toolKey());
			return !requested.isEmpty() && requested.equals(normalizeToolWireName(item.key()));
		}
		return false;
	}

	/** {@code app.navigate} / {@code app-navigate} / {@code app_navigate} → same wire form. */
	static String normalizeToolWireName(String name) {
		if (name == null) {
			return "";
		}
		return name.replace('.', '_').replace('-', '_').toLowerCase(Locale.ROOT);
	}

	/** Placeholder descriptor so memory/verifier can record an invented tool name. */
	private static ToolDescriptor phantomUnknownTool(String requestedKey) {
		return new ToolDescriptor(null, requestedKey, "Unknown tool (model invented this name)",
			"{\"type\":\"object\"}", ToolType.BUILD_IN, requestedKey, Map.of(), Map.of());
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

	/**
	 * Recoverable result when the model invents a tool that is not in the assistant catalog.
	 * Includes tools available this turn so the next planner step can switch correctly.
	 * Phrase "unknown tool" is intentional — {@link FailureClassifier} maps it to TOOL_EXHAUSTED.
	 */
	static ToolExecutionResult syntheticUnknownTool(String requestedKey, List<ToolDescriptor> availableThisTurn) {
		String label = requestedKey == null || requestedKey.isBlank() ? "(empty)" : requestedKey.trim();
		List<String> keys = availableThisTurn == null
			? List.of()
			: availableThisTurn.stream()
				.map(ToolDescriptor::key)
				.filter(key -> key != null && !key.isBlank())
				.distinct()
				.sorted()
				.limit(40)
				.toList();
		String available = keys.isEmpty() ? "(none listed this turn)" : String.join(", ", keys);
		String message = "Unknown tool '" + label + "'. That tool does not exist or is not available this turn. "
			+ "Available tools this turn: " + available + ". "
			+ "Use only those names. If you need an API/HTTP tool, call agent.use_api then tool.search/tool.activate. "
			+ "If you need page/navigation tools, call agent.use_browser. Do not invent tool names.";
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
		// Ensure navigate paths are app-root absolute so host routers do not resolve them relative to the
		// current page (e.g. "deepak/60min" on /event-types → /event-types/deepak/60min).
		if (isNavigateTool(tool)) {
			Object path = merged.get("path");
			if (path != null) {
				String normalized = normalizeAppNavigatePath(String.valueOf(path));
				if (!normalized.isBlank()) {
					merged.put("path", normalized);
				}
			}
		}
		return merged;
	}

	/**
	 * Paths without a leading slash become root-absolute. Schemes, ?, and # are left alone.
	 */
	static String normalizeAppNavigatePath(String path) {
		if (path == null) {
			return "";
		}
		String trimmed = path.trim();
		if (trimmed.isEmpty()) {
			return trimmed;
		}
		char first = trimmed.charAt(0);
		if (first == '/' || first == '?' || first == '#') {
			return trimmed;
		}
		if (trimmed.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")) {
			return trimmed;
		}
		return "/" + trimmed;
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
				+ "Structured observation when the user sent this message — interactive elements, headings, path. "
				+ "Prefer answering from this; call page.observe only if it is missing or insufficient. "
				+ "Do not invent page content beyond this snapshot.) ---\n"
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

		// Harness-first system prompt: static product rules here; per-step TURN CONTRACT + specialist
		// state are injected via runtime guidance (modular context — avoids curse-of-instructions bloat).
		prompt.append(HarnessPromptContract.turnEfficiencyContract())
			.append(HarnessPromptContract.failureRecoveryRules())
			.append(HarnessPromptContract.verifyBeforeDoneRules())
			.append("OPERATING MODE: In-app assistant for a host web app. Two specialists share the run; only one is active. The harness auto-routes when intent is pure (search/activate/direct catalog call).\n")
			.append("\n")
			.append("SPECIALIST ROUTING:\n")
			.append("  - tool.search / tool.activate — pure-domain hits auto-switch + activate (prefer this over agent.use_*).\n")
			.append("  - Direct call of a real catalog tool auto-routes if needed, then runs.\n")
			.append("  - agent.use_browser / agent.use_api — explicit force only.\n")
			.append("  - Active specialist is restated each step in runtime guidance.\n")
			.append("\n")
			.append("Browser tools: path.find | page.observe | page.screenshot | app.navigate\n")
			.append("  - path.find: current path/url/title. page.observe: structured page snapshot (prefer over screenshot).\n")
			.append("  - page.screenshot: vision/fallback only. app.navigate: absolute app-root path starting with /;\n")
			.append("    never prefix with current path (on /event-types go to /deepak/60min not /event-types/deepak/60min).\n")
			.append("    Prefer observed link href. Success includes pageObserve — do not re-observe same turn.\n")
			.append("\n")
			.append("Shared: knowledge.search — operator knowledge only when page tools cannot answer; at most once per turn; no inventing.\n")
			.append("API: operator HTTP/MCP tools after search/auto-route. Multiple independent APIs in one step run in parallel.\n")
			.append("\n")
			.append("HARD RULES:\n")
			.append("  1. Only schema / tool.search keys. Never invent tools, paths, or on-screen facts.\n")
			.append("  1a. app.navigate paths are root-absolute; use href when present.\n")
			.append("  2. Page facts only from PAGE_CONTEXT or successful page.observe / navigate pageObserve / screenshot fields.\n")
			.append("  2a. Every concrete name/label/price must appear verbatim in that evidence. knowledge.search is not page content.\n")
			.append("  3. On tool failure: honest + recoverable next action (see ON TOOL FAILURE). No same-args retry.\n")
			.append("  4. PAGE_CONTEXT / navigate pageObserve counts as observation. page.observe|screenshot ≤1/turn; knowledge.search ≤1/turn.\n")
			.append("  5. Never same tool+args twice. After two useless observations, stop and say what you tried.\n")
			.append("  6. No action tool → tell the user what to do themselves, grounded in real observation only.\n")
			.append("  7. Unclear ask → one clarification with OPTIONS when choices exist (see below).\n")
			.append("\n")
			.append("CLICKABLE CHOICES (picks, not prose):\n")
			.append("  - Clarifications and short observed lists end with:\n")
			.append("    OPTIONS: label one | label two\n")
			.append("    RECOMMENDED: label one\n")
			.append("  - Labels verbatim from evidence. ≤4 options. Line must start with OPTIONS: (no bullets/bold).\n")
			.append("  - Act only after the user picks.\n")
			.append("\n")
			.append("GUIDED WALKTHROUGHS:\n")
			.append("  - One navigation per user message; then final answer + OPTIONS to continue.\n")
			.append("  - Never silent multi-page tours.\n")
			.append("\n")
			.append("Cheapest correct outcome: a final answer. Tools only when they unlock an answer you lack.\n\n");

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
