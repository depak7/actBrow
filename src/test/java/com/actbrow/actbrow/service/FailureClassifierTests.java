package com.actbrow.actbrow.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.actbrow.actbrow.agent.ToolDescriptor;
import com.actbrow.actbrow.agent.ToolExecutionResult;
import com.actbrow.actbrow.model.FailureType;
import com.actbrow.actbrow.model.ToolType;

class FailureClassifierTests {

	private final FailureClassifier classifier = new FailureClassifier();

	private final ToolDescriptor serverTool = new ToolDescriptor("tool-1", "orders.fetch", "Fetch order", "{}",
		ToolType.SERVER_HTTP, "orders.fetch", Map.of(), Map.of());
	private final ToolDescriptor clientTool = new ToolDescriptor("tool-2", "app.navigate.profile", "Open profile", "{}",
		ToolType.CLIENT, "app.navigate", Map.of("path", "/profile"), Map.of());

	private ToolExecutionResult failure(String text) {
		return new ToolExecutionResult(false, null, text, text);
	}

	@Test
	void successIsNone() {
		assertThat(classifier.classify(serverTool, new ToolExecutionResult(true, "{}", "ok", null)))
			.isEqualTo(FailureType.NONE);
	}

	@Test
	void nullResultIsUnknown() {
		assertThat(classifier.classify(serverTool, null)).isEqualTo(FailureType.UNKNOWN);
	}

	@Test
	void exhaustionBeatsOtherSignals() {
		assertThat(classifier.classify(serverTool, failure("retry budget exhausted for this tool")))
			.isEqualTo(FailureType.TOOL_EXHAUSTED);
	}

	@Test
	void authIsClassified() {
		assertThat(classifier.classify(serverTool, failure("401 unauthorized, please sign in")))
			.isEqualTo(FailureType.AUTH_ERROR);
	}

	@Test
	void rateLimitBeatsTimeoutWhenBothPresent() {
		assertThat(classifier.classify(serverTool, failure("429 rate limit exceeded")))
			.isEqualTo(FailureType.RATE_LIMITED);
	}

	@Test
	void timeoutIsClassified() {
		assertThat(classifier.classify(serverTool, failure("request timed out")))
			.isEqualTo(FailureType.TIMEOUT);
	}

	@Test
	void serverErrorIsClassified() {
		assertThat(classifier.classify(serverTool, failure("upstream returned 503")))
			.isEqualTo(FailureType.SERVER_ERROR);
	}

	@Test
	void notFoundIsClassified() {
		assertThat(classifier.classify(serverTool, failure("resource not found")))
			.isEqualTo(FailureType.NOT_FOUND);
	}

	@Test
	void invalidArgumentsIsClassified() {
		assertThat(classifier.classify(serverTool, failure("schema validation failed: missing field")))
			.isEqualTo(FailureType.INVALID_ARGUMENTS);
	}

	@Test
	void clientIncompleteOnlyForClientPendingTools() {
		assertThat(classifier.classify(clientTool, failure("could not complete the action")))
			.isEqualTo(FailureType.CLIENT_INCOMPLETE);
		// Same signal on a server tool is not a client-incomplete case.
		assertThat(classifier.classify(serverTool, failure("could not complete the action")))
			.isEqualTo(FailureType.UNKNOWN);
	}

	@Test
	void unrecognizedFailureIsUnknown() {
		assertThat(classifier.classify(serverTool, failure("something weird happened")))
			.isEqualTo(FailureType.UNKNOWN);
	}
}
