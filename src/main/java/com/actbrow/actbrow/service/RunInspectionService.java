package com.actbrow.actbrow.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.actbrow.actbrow.api.NotFoundException;
import com.actbrow.actbrow.api.dto.RunInspectionResponse;
import com.actbrow.actbrow.api.dto.RunStepResponse;
import com.actbrow.actbrow.api.dto.RunSummaryResponse;
import com.actbrow.actbrow.api.dto.TraceResponse;
import com.actbrow.actbrow.model.RunEntity;
import com.actbrow.actbrow.model.RunStepEntity;
import com.actbrow.actbrow.model.RunStepType;
import com.actbrow.actbrow.model.RunTraceEntity;
import com.actbrow.actbrow.repository.RunRepository;
import com.actbrow.actbrow.repository.RunStepRepository;
import com.actbrow.actbrow.repository.RunTraceRepository;

/**
 * Read-only projection of stored run state for the dashboard's run inspector. Callers must have
 * already been authorized via {@link ResourceAuthorizationService}; nothing here is tenant-aware.
 */
@Service
public class RunInspectionService {

	/** Step payloads can hold entire HTTP bodies; cap them so a single run cannot blow up a response. */
	static final int MAX_PAYLOAD_CHARS = 8_000;

	static final String TRUNCATION_SUFFIX = "…(truncated)";

	/**
	 * Matches a credential-ish key followed by its value. Groups 1-2 (key, separator and any auth
	 * scheme such as "Bearer") are kept so the payload stays readable; group 3 — the actual value —
	 * is replaced. Step payloads are raw tool request/response dumps, so real tokens and API keys do
	 * end up in them and must never reach a browser.
	 */
	private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
		"((?i:authorization|api[-_]?key|password|secret|token)\"?\\s*[=:]\\s*)((?i:bearer|basic|token)\\s+)?"
			+ "(\"[^\"]*\"|[^\\s,;&}\\]]+)");

	/** Matches both the record {@code toString()} form and JSON-encoded tool results. */
	private static final Pattern FAILED_RESULT = Pattern.compile("success=false|\"success\"\\s*:\\s*false",
		Pattern.CASE_INSENSITIVE);

	private static final String REDACTED = "***";

	private final RunRepository runRepository;
	private final RunStepRepository runStepRepository;
	private final RunTraceRepository runTraceRepository;

	public RunInspectionService(RunRepository runRepository, RunStepRepository runStepRepository,
		RunTraceRepository runTraceRepository) {
		this.runRepository = runRepository;
		this.runStepRepository = runStepRepository;
		this.runTraceRepository = runTraceRepository;
	}

	/**
	 * Lists every run of a conversation, newest first. Tool counters are derived from the run's steps
	 * because they are not denormalised onto the run row.
	 */
	@Transactional(readOnly = true)
	public List<RunSummaryResponse> listConversationRuns(String conversationId) {
		List<RunEntity> runs = new ArrayList<>(runRepository.findAllByConversationId(conversationId));
		runs.sort(Comparator.comparing(RunEntity::getCreatedAt,
			Comparator.nullsLast(Comparator.naturalOrder())).reversed());
		List<RunSummaryResponse> summaries = new ArrayList<>(runs.size());
		for (RunEntity run : runs) {
			List<RunStepEntity> steps = runStepRepository.findAllByRunIdOrderByStepIndexAsc(run.getId());
			summaries.add(new RunSummaryResponse(run.getId(), run.getStatus(), run.getStepCount(), run.getLastError(),
				run.getCreatedAt(), run.getCompletedAt(), durationMs(run), countToolCalls(steps),
				countFailedTools(steps)));
		}
		return summaries;
	}

	/** Full step-by-step view of one run, including its evaluation trace when the run finished. */
	@Transactional(readOnly = true)
	public RunInspectionResponse inspect(String runId) {
		RunEntity run = runRepository.findById(runId)
			.orElseThrow(() -> new NotFoundException("Run not found"));
		List<RunStepResponse> steps = runStepRepository.findAllByRunIdOrderByStepIndexAsc(runId).stream()
			.map(this::toStepResponse)
			.toList();
		TraceResponse trace = runTraceRepository.findByRunId(runId)
			.map(RunInspectionService::toTraceResponse)
			.orElse(null);
		return new RunInspectionResponse(run.getId(), run.getStatus(), run.getStepCount(), run.getLastError(),
			run.getCreatedAt(), run.getCompletedAt(), durationMs(run), steps, trace);
	}

	private RunStepResponse toStepResponse(RunStepEntity step) {
		return new RunStepResponse(step.getId(), step.getStepIndex(), step.getType(),
			truncate(redactSecrets(step.getPayload())), step.getCreatedAt());
	}

	/**
	 * Blanks out the value of anything that looks like a credential assignment. Applied before
	 * truncation so a secret cannot survive by sitting near the cut-off point.
	 */
	private String redactSecrets(String payload) {
		if (payload == null || payload.isEmpty()) {
			return payload;
		}
		return SECRET_ASSIGNMENT.matcher(payload).replaceAll("$1$2" + REDACTED);
	}

	private String truncate(String payload) {
		if (payload == null || payload.length() <= MAX_PAYLOAD_CHARS) {
			return payload;
		}
		return payload.substring(0, MAX_PAYLOAD_CHARS) + TRUNCATION_SUFFIX;
	}

	/** Null while the run is still active — the UI shows "running" rather than a bogus duration. */
	private Long durationMs(RunEntity run) {
		if (run.getCreatedAt() == null || run.getCompletedAt() == null) {
			return null;
		}
		return Duration.between(run.getCreatedAt(), run.getCompletedAt()).toMillis();
	}

	private int countToolCalls(List<RunStepEntity> steps) {
		return (int) steps.stream().filter(s -> s.getType() == RunStepType.TOOL_CALL).count();
	}

	/**
	 * A failed tool shows up as a TOOL_RESULT step whose payload reports success=false; the executor
	 * records failures as ordinary results rather than as a distinct step type.
	 */
	private int countFailedTools(List<RunStepEntity> steps) {
		int failed = 0;
		for (RunStepEntity step : steps) {
			if (step.getType() != RunStepType.TOOL_RESULT) {
				continue;
			}
			String payload = step.getPayload() == null ? "" : step.getPayload();
			if (FAILED_RESULT.matcher(payload).find()) {
				failed++;
			}
		}
		return failed;
	}

	private static TraceResponse toTraceResponse(RunTraceEntity trace) {
		return new TraceResponse(trace.getId(), trace.getRunId(), trace.getConversationId(), trace.getAssistantId(),
			trace.getPromptVersion(), trace.getToolsetVersion(), trace.getPlanningOutcomes(),
			trace.getVerifierDecisions(), trace.getExecutionAttempts(), trace.getToolCallCount(),
			trace.getFinalOutcome(), trace.getLatencyMs(), trace.getCreatedAt());
	}
}
