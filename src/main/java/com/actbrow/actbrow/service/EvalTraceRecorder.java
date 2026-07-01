package com.actbrow.actbrow.service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.actbrow.actbrow.model.RunEntity;
import com.actbrow.actbrow.model.RunTraceEntity;
import com.actbrow.actbrow.repository.RunTraceRepository;

/**
 * Accumulates a structured plan/act/verify trace per run in memory and persists it when the run
 * reaches a terminal state (Phase 5). Enables measuring recovery success and false-completion rate,
 * and inspecting a completed run as a full trace via {@link #findTrace(String)}.
 */
@Service
public class EvalTraceRecorder {

	private static final Logger log = LoggerFactory.getLogger(EvalTraceRecorder.class);

	private final RunTraceRepository repository;
	private final ConcurrentHashMap<String, Builder> inFlight = new ConcurrentHashMap<>();

	public EvalTraceRecorder(RunTraceRepository repository) {
		this.repository = repository;
	}

	public void begin(RunEntity run, String promptVersion, String toolsetVersion) {
		Builder builder = new Builder(run.getConversationId(), run.getAssistantId(), promptVersion, toolsetVersion);
		inFlight.put(run.getId(), builder);
	}

	public void recordPlanning(String runId, String outcome) {
		Builder builder = inFlight.get(runId);
		if (builder != null) {
			builder.planningOutcomes.add(outcome);
		}
	}

	public void recordExecutionAttempt(String runId) {
		Builder builder = inFlight.get(runId);
		if (builder != null) {
			builder.executionAttempts.incrementAndGet();
			builder.toolCallCount.incrementAndGet();
		}
	}

	public void recordVerifier(String runId, String decision) {
		Builder builder = inFlight.get(runId);
		if (builder != null) {
			builder.verifierDecisions.add(decision);
		}
	}

	/** Persist the accumulated trace and drop the in-memory builder. Safe to call once per run. */
	public void finalizeTrace(String runId, String finalOutcome, long latencyMs) {
		Builder builder = inFlight.remove(runId);
		if (builder == null) {
			return;
		}
		try {
			RunTraceEntity entity = new RunTraceEntity();
			entity.setRunId(runId);
			entity.setConversationId(builder.conversationId);
			entity.setAssistantId(builder.assistantId);
			entity.setPromptVersion(builder.promptVersion);
			entity.setToolsetVersion(builder.toolsetVersion);
			entity.setPlanningOutcomes(String.join("\n", builder.planningOutcomes));
			entity.setVerifierDecisions(String.join("\n", builder.verifierDecisions));
			entity.setExecutionAttempts(builder.executionAttempts.get());
			entity.setToolCallCount(builder.toolCallCount.get());
			entity.setFinalOutcome(finalOutcome);
			entity.setLatencyMs(latencyMs);
			repository.save(entity);
		}
		catch (Exception exception) {
			// Tracing must never break a run — log and move on.
			log.warn("Failed to persist run trace for {}", runId, exception);
		}
	}

	public Optional<RunTraceEntity> findTrace(String runId) {
		return repository.findByRunId(runId);
	}

	private static final class Builder {
		private final String conversationId;
		private final String assistantId;
		private final String promptVersion;
		private final String toolsetVersion;
		private final List<String> planningOutcomes = new CopyOnWriteArrayList<>();
		private final List<String> verifierDecisions = new CopyOnWriteArrayList<>();
		private final AtomicInteger executionAttempts = new AtomicInteger();
		private final AtomicInteger toolCallCount = new AtomicInteger();

		private Builder(String conversationId, String assistantId, String promptVersion, String toolsetVersion) {
			this.conversationId = conversationId;
			this.assistantId = assistantId;
			this.promptVersion = promptVersion;
			this.toolsetVersion = toolsetVersion;
		}
	}
}
