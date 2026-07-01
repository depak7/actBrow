package com.actbrow.actbrow.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.actbrow.actbrow.agent.ToolExecutionResult;
import com.fasterxml.jackson.databind.ObjectMapper;

class RunToolFailureTrackerTests {

	private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

	@Test
	void duplicateExactCallIsBlocked() {
		RunToolFailureTracker tracker = new RunToolFailureTracker(objectMapper, 2);

		tracker.recordResult("orders.fetch", "orders.fetch|{\"id\":\"123\"}",
			new ToolExecutionResult(false, null, "first failure", "first failure"));

		RunToolFailureTracker.GuardDecision guard = tracker.beforeToolCall("orders.fetch",
			"orders.fetch|{\"id\":\"123\"}");

		assertThat(guard.allowed()).isFalse();
		assertThat(guard.syntheticResult()).isNotNull();
		assertThat(guard.syntheticResult().error()).contains("exact arguments");
		assertThat(guard.syntheticResult().structuredOutput()).contains("duplicate_call");
	}

	@Test
	void runtimeGuidanceIncludesRecentFailuresAndRetryState() {
		RunToolFailureTracker tracker = new RunToolFailureTracker(objectMapper, 2);

		tracker.recordResult("orders.fetch", "sig-1",
			new ToolExecutionResult(false, null, "400 missing customerId", "400 missing customerId"));
		tracker.recordResult("orders.update", "sig-2",
			new ToolExecutionResult(false, null, "503 upstream unavailable", "503 upstream unavailable"));

		String guidance = tracker.buildRuntimeGuidance();

		assertThat(guidance).contains("RUNTIME RETRY STATE FOR THIS RUN");
		assertThat(guidance).contains("orders.fetch failure #1");
		assertThat(guidance).contains("orders.update failure #1");
		assertThat(guidance).contains("retry slot(s) left");
	}

	@Test
	void executorFailureResultProducesStructuredRepairHints() throws Exception {
		RunToolFailureTracker tracker = new RunToolFailureTracker(objectMapper, 1);

		ToolExecutionResult result = tracker.executorFailureResult("orders.fetch",
			new IllegalStateException("503 from upstream"));

		assertThat(result.success()).isFalse();
		assertThat(result.textSummary()).containsIgnoringCase("retry");
		Map<?, ?> parsed = objectMapper.readValue(result.structuredOutput(), Map.class);
		assertThat(parsed.get("errorType")).isEqualTo("executor_failure");
		assertThat(parsed.get("tool")).isEqualTo("orders.fetch");
		assertThat(parsed.get("remainingRetriesForTool")).isEqualTo(0);
	}
}
