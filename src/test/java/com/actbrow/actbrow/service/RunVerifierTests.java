package com.actbrow.actbrow.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.actbrow.actbrow.agent.ToolDescriptor;
import com.actbrow.actbrow.agent.ToolExecutionResult;
import com.actbrow.actbrow.model.ToolType;

class RunVerifierTests {

	private final RunVerifier runVerifier = new RunVerifier(new FailureClassifier());
	private final ToolDescriptor serverTool = new ToolDescriptor("tool-1", "orders.fetch", "Fetch order", "{}",
		ToolType.SERVER_HTTP, "orders.fetch", java.util.Map.of(), java.util.Map.of());
	private final ToolDescriptor clientTool = new ToolDescriptor("tool-2", "app.navigate.profile", "Open profile", "{}",
		ToolType.CLIENT, "app.navigate", java.util.Map.of("path", "/profile"), java.util.Map.of());

	@Test
	void successfulToolIsVerifiedAsSucceeded() {
		RunVerifier.VerificationDecision decision = runVerifier.verify(serverTool,
			new ToolExecutionResult(true, "{\"status\":\"ok\"}", "ok", null));

		assertThat(decision.status()).isEqualTo(RunVerifier.VerificationStatus.SUCCEEDED);
		assertThat(decision.yieldToPlanner()).isFalse();
	}

	@Test
	void timeoutIsVerifiedAsRetryableFailure() {
		RunVerifier.VerificationDecision decision = runVerifier.verify(serverTool,
			new ToolExecutionResult(false, null, "request timed out", "request timed out"));

		assertThat(decision.status()).isEqualTo(RunVerifier.VerificationStatus.RETRYABLE_FAILURE);
		assertThat(decision.yieldToPlanner()).isTrue();
	}

	@Test
	void authStyleClientFailureNeedsUserInput() {
		RunVerifier.VerificationDecision decision = runVerifier.verify(clientTool,
			new ToolExecutionResult(false, null, "Please sign in again", "authentication required"));

		assertThat(decision.status()).isEqualTo(RunVerifier.VerificationStatus.NEEDS_USER_INPUT);
		assertThat(decision.yieldToPlanner()).isTrue();
	}

	@Test
	void exhaustedRetryPathIsTerminal() {
		RunVerifier.VerificationDecision decision = runVerifier.verify(serverTool,
			new ToolExecutionResult(false, null, "Stop using this tool in this run", "retry budget exhausted"));

		assertThat(decision.status()).isEqualTo(RunVerifier.VerificationStatus.TERMINAL_FAILURE);
		assertThat(decision.yieldToPlanner()).isTrue();
	}
}
