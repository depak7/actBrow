package com.actbrow.actbrow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import com.actbrow.actbrow.api.dto.RunInspectionResponse;
import com.actbrow.actbrow.api.dto.RunSummaryResponse;
import com.actbrow.actbrow.model.RunEntity;
import com.actbrow.actbrow.model.RunStatus;
import com.actbrow.actbrow.model.RunStepEntity;
import com.actbrow.actbrow.model.RunStepType;
import com.actbrow.actbrow.model.RunTraceEntity;
import com.actbrow.actbrow.repository.RunRepository;
import com.actbrow.actbrow.repository.RunStepRepository;
import com.actbrow.actbrow.repository.RunTraceRepository;

class RunInspectionServiceTests {

	@Mock
	private RunRepository runRepository;

	@Mock
	private RunStepRepository runStepRepository;

	@Mock
	private RunTraceRepository runTraceRepository;

	private RunInspectionService runInspectionService;

	@BeforeEach
	void setUp() {
		MockitoAnnotations.openMocks(this);
		runInspectionService = new RunInspectionService(runRepository, runStepRepository, runTraceRepository);
	}

	@Test
	void inspectReturnsStepsInStepIndexOrderWithRunMetadata() {
		Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
		RunEntity run = run("run-1", RunStatus.COMPLETED, createdAt, createdAt.plusMillis(1_500));
		run.setStepCount(3);
		when(runRepository.findById("run-1")).thenReturn(Optional.of(run));
		when(runStepRepository.findAllByRunIdOrderByStepIndexAsc("run-1")).thenReturn(List.of(
			step("s-0", 0, RunStepType.MODEL_DECISION, "decide"),
			step("s-1", 1, RunStepType.TOOL_CALL, "orders.update"),
			step("s-2", 2, RunStepType.FINAL_RESPONSE, "done")));
		when(runTraceRepository.findByRunId("run-1")).thenReturn(Optional.empty());

		RunInspectionResponse response = runInspectionService.inspect("run-1");

		assertThat(response.runId()).isEqualTo("run-1");
		assertThat(response.status()).isEqualTo(RunStatus.COMPLETED);
		assertThat(response.stepCount()).isEqualTo(3);
		assertThat(response.durationMs()).isEqualTo(1_500L);
		assertThat(response.steps()).extracting(s -> s.stepIndex()).containsExactly(0, 1, 2);
		assertThat(response.steps()).extracting(s -> s.type())
			.containsExactly(RunStepType.MODEL_DECISION, RunStepType.TOOL_CALL, RunStepType.FINAL_RESPONSE);
	}

	@Test
	void inspectTruncatesOversizedPayloads() {
		RunEntity run = run("run-2", RunStatus.COMPLETED, Instant.now(), null);
		when(runRepository.findById("run-2")).thenReturn(Optional.of(run));
		String huge = "x".repeat(RunInspectionService.MAX_PAYLOAD_CHARS + 500);
		when(runStepRepository.findAllByRunIdOrderByStepIndexAsc("run-2"))
			.thenReturn(List.of(step("s-0", 0, RunStepType.TOOL_RESULT, huge)));
		when(runTraceRepository.findByRunId("run-2")).thenReturn(Optional.empty());

		String payload = runInspectionService.inspect("run-2").steps().get(0).payload();

		assertThat(payload).endsWith(RunInspectionService.TRUNCATION_SUFFIX);
		assertThat(payload)
			.hasSize(RunInspectionService.MAX_PAYLOAD_CHARS + RunInspectionService.TRUNCATION_SUFFIX.length());
	}

	@Test
	void inspectRedactsCredentialValuesInPayloads() {
		RunEntity run = run("run-3", RunStatus.COMPLETED, Instant.now(), null);
		when(runRepository.findById("run-3")).thenReturn(Optional.of(run));
		String leaky = "GET /orders headers={Authorization: Bearer sk-live-abc123} "
			+ "body={\"api_key\":\"sk-secret-9\",\"password\":\"hunter2\",\"orderId\":\"ord_1\"} token=tok_zzz";
		when(runStepRepository.findAllByRunIdOrderByStepIndexAsc("run-3"))
			.thenReturn(List.of(step("s-0", 0, RunStepType.TOOL_CALL, leaky)));
		when(runTraceRepository.findByRunId("run-3")).thenReturn(Optional.empty());

		String payload = runInspectionService.inspect("run-3").steps().get(0).payload();

		assertThat(payload).doesNotContain("sk-live-abc123", "sk-secret-9", "hunter2", "tok_zzz");
		assertThat(payload).contains("***");
		// Non-secret context must survive so the step is still debuggable.
		assertThat(payload).contains("ord_1").contains("GET /orders");
	}

	@Test
	void inspectReturnsNullTraceWhenNoneRecorded() {
		RunEntity run = run("run-4", RunStatus.IN_PROGRESS, Instant.now(), null);
		when(runRepository.findById("run-4")).thenReturn(Optional.of(run));
		when(runStepRepository.findAllByRunIdOrderByStepIndexAsc("run-4")).thenReturn(List.of());
		when(runTraceRepository.findByRunId("run-4")).thenReturn(Optional.empty());

		RunInspectionResponse response = runInspectionService.inspect("run-4");

		assertThat(response.trace()).isNull();
		assertThat(response.steps()).isEmpty();
		assertThat(response.durationMs()).isNull();
	}

	@Test
	void inspectMapsTraceWhenRecorded() {
		RunEntity run = run("run-5", RunStatus.FAILED, Instant.now(), null);
		when(runRepository.findById("run-5")).thenReturn(Optional.of(run));
		when(runStepRepository.findAllByRunIdOrderByStepIndexAsc("run-5")).thenReturn(List.of());
		RunTraceEntity trace = new RunTraceEntity();
		trace.setRunId("run-5");
		trace.setConversationId("conv-5");
		trace.setAssistantId("asst-5");
		trace.setFinalOutcome("failed");
		trace.setExecutionAttempts(2);
		trace.setToolCallCount(3);
		trace.setLatencyMs(4_200L);
		when(runTraceRepository.findByRunId("run-5")).thenReturn(Optional.of(trace));

		RunInspectionResponse response = runInspectionService.inspect("run-5");

		assertThat(response.trace()).isNotNull();
		assertThat(response.trace().runId()).isEqualTo("run-5");
		assertThat(response.trace().executionAttempts()).isEqualTo(2);
		assertThat(response.trace().toolCallCount()).isEqualTo(3);
		assertThat(response.trace().observeCount()).isEqualTo(0);
		assertThat(response.trace().screenshotCount()).isEqualTo(0);
		assertThat(response.trace().clientToolWaitMs()).isEqualTo(0L);
		assertThat(response.trace().latencyMs()).isEqualTo(4_200L);
		assertThat(response.trace().finalOutcome()).isEqualTo("failed");
	}

	@Test
	void listConversationRunsDerivesToolCountersAndOrdersNewestFirst() {
		Instant older = Instant.parse("2026-01-01T00:00:00Z");
		Instant newer = Instant.parse("2026-01-02T00:00:00Z");
		RunEntity oldRun = run("run-old", RunStatus.COMPLETED, older, older.plusMillis(1_000));
		RunEntity newRun = run("run-new", RunStatus.FAILED, newer, newer.plusMillis(2_000));
		newRun.setLastError("boom");
		when(runRepository.findAllByConversationId("conv-1")).thenReturn(List.of(oldRun, newRun));
		when(runStepRepository.findAllByRunIdOrderByStepIndexAsc("run-old")).thenReturn(List.of(
			step("a-0", 0, RunStepType.TOOL_CALL, "orders.list"),
			step("a-1", 1, RunStepType.TOOL_RESULT, "ToolExecutionResult[success=true]")));
		when(runStepRepository.findAllByRunIdOrderByStepIndexAsc("run-new")).thenReturn(List.of(
			step("b-0", 0, RunStepType.TOOL_CALL, "orders.update"),
			step("b-1", 1, RunStepType.TOOL_RESULT, "ToolExecutionResult[success=false, error=409]"),
			step("b-2", 2, RunStepType.TOOL_CALL, "orders.update"),
			step("b-3", 3, RunStepType.TOOL_RESULT, "{\"success\": false, \"error\":\"409\"}"),
			step("b-4", 4, RunStepType.MODEL_DECISION, "give up")));

		List<RunSummaryResponse> summaries = runInspectionService.listConversationRuns("conv-1");

		assertThat(summaries).extracting(RunSummaryResponse::id).containsExactly("run-new", "run-old");

		RunSummaryResponse newest = summaries.get(0);
		assertThat(newest.toolCallCount()).isEqualTo(2);
		assertThat(newest.failedToolCount()).isEqualTo(2);
		assertThat(newest.lastError()).isEqualTo("boom");
		assertThat(newest.durationMs()).isEqualTo(2_000L);

		RunSummaryResponse oldest = summaries.get(1);
		assertThat(oldest.toolCallCount()).isEqualTo(1);
		assertThat(oldest.failedToolCount()).isZero();
		assertThat(oldest.durationMs()).isEqualTo(1_000L);
	}

	private static RunEntity run(String id, RunStatus status, Instant createdAt, Instant completedAt) {
		RunEntity run = new RunEntity();
		run.setId(id);
		run.setConversationId("conv-1");
		run.setAssistantId("asst-1");
		run.setStatus(status);
		// createdAt has no setter (it is assigned in @PrePersist), so seed it directly for tests.
		ReflectionTestUtils.setField(run, "createdAt", createdAt);
		run.setCompletedAt(completedAt);
		return run;
	}

	private static RunStepEntity step(String id, int stepIndex, RunStepType type, String payload) {
		RunStepEntity step = new RunStepEntity();
		step.setId(id);
		step.setRunId("run-1");
		step.setStepIndex(stepIndex);
		step.setType(type);
		step.setPayload(payload);
		ReflectionTestUtils.setField(step, "createdAt", Instant.parse("2026-01-01T00:00:00Z"));
		return step;
	}
}
