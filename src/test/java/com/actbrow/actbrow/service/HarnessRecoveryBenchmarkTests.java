package com.actbrow.actbrow.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.actbrow.actbrow.agent.ToolDescriptor;
import com.actbrow.actbrow.agent.ToolExecutionResult;
import com.actbrow.actbrow.model.ToolType;
import com.actbrow.actbrow.service.RunPolicyEngine.PolicyAction;

/**
 * Benchmark scenarios for the classify → policy pipeline (Phase 5 acceptance). Locks in that the
 * happy path and each failure-recovery path resolve to the intended deterministic action so
 * regressions in recovery behavior are caught.
 */
class HarnessRecoveryBenchmarkTests {

	private final FailureClassifier classifier = new FailureClassifier();
	private final RunPolicyEngine policy = new RunPolicyEngine();

	private final ToolDescriptor serverTool = new ToolDescriptor("tool-1", "orders.fetch", "Fetch", "{}",
		ToolType.SERVER_HTTP, "orders.fetch", Map.of(), Map.of());

	private PolicyAction actionFor(ToolExecutionResult result, boolean alternatives) {
		return policy.decide(classifier.classify(serverTool, result), alternatives).action();
	}

	private ToolExecutionResult ok() {
		return new ToolExecutionResult(true, "{\"ok\":true}", "ok", null);
	}

	private ToolExecutionResult fail(String text) {
		return new ToolExecutionResult(false, null, text, text);
	}

	@Test
	void happyPathContinues() {
		assertThat(actionFor(ok(), true)).isEqualTo(PolicyAction.CONTINUE);
	}

	@Test
	void authBlocksOnUser() {
		assertThat(actionFor(fail("401 unauthorized"), true)).isEqualTo(PolicyAction.REQUIRE_USER_INTERVENTION);
	}

	@Test
	void rateLimitRetries() {
		assertThat(actionFor(fail("429 rate limit"), false)).isEqualTo(PolicyAction.RETRY_WITH_REPAIRED_ARGUMENTS);
	}

	@Test
	void invalidArgumentsRetry() {
		assertThat(actionFor(fail("schema validation failed"), false))
			.isEqualTo(PolicyAction.RETRY_WITH_REPAIRED_ARGUMENTS);
	}

	@Test
	void exhaustionSwitchesWhenAlternativesExist() {
		assertThat(actionFor(fail("retry budget exhausted"), true)).isEqualTo(PolicyAction.SWITCH_TOOL);
	}

	@Test
	void exhaustionStopsWhenNoAlternative() {
		assertThat(actionFor(fail("retry budget exhausted"), false)).isEqualTo(PolicyAction.STOP_WITH_EXPLANATION);
	}
}
