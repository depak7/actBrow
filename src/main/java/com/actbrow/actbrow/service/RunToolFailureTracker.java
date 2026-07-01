package com.actbrow.actbrow.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.actbrow.actbrow.agent.ToolExecutionResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Tracks tool attempts within a single run so the model can recover from failures
 * instead of repeating the same broken call until the run hard-fails.
 */
final class RunToolFailureTracker {

	private final ObjectMapper objectMapper;
	private final int maxToolRetries;
	private final Map<String, Integer> attemptsByTool = new LinkedHashMap<>();
	private final Map<String, Integer> failuresByTool = new LinkedHashMap<>();
	private final Map<String, Integer> attemptsBySignature = new LinkedHashMap<>();
	private final Deque<FailureRecord> recentFailures = new ArrayDeque<>();

	RunToolFailureTracker(ObjectMapper objectMapper, int maxToolRetries) {
		this.objectMapper = objectMapper;
		this.maxToolRetries = Math.max(0, maxToolRetries);
	}

	GuardDecision beforeToolCall(String toolKey, String signature) {
		Integer priorExactAttempts = attemptsBySignature.get(signature);
		if (priorExactAttempts != null) {
			return new GuardDecision(false, duplicateCallResult(toolKey, priorExactAttempts));
		}
		int toolFailures = failuresByTool.getOrDefault(toolKey, 0);
		if (toolFailures > maxToolRetries) {
			return new GuardDecision(false, retryBudgetExceededResult(toolKey, toolFailures));
		}
		return new GuardDecision(true, null);
	}

	void recordResult(String toolKey, String signature, ToolExecutionResult result) {
		attemptsByTool.merge(toolKey, 1, Integer::sum);
		attemptsBySignature.merge(signature, 1, Integer::sum);
		if (result == null || result.success()) {
			return;
		}
		int failureCount = failuresByTool.merge(toolKey, 1, Integer::sum);
		FailureRecord record = new FailureRecord(toolKey, failureCount, compactError(result));
		recentFailures.addLast(record);
		while (recentFailures.size() > 4) {
			recentFailures.removeFirst();
		}
	}

	ToolExecutionResult executorFailureResult(String toolKey, Exception exception) {
		String error = exception == null ? "Tool execution failed." : compactThrowable(exception);
		int nextFailureNumber = failuresByTool.getOrDefault(toolKey, 0) + 1;
		int retriesLeft = Math.max(0, maxToolRetries - nextFailureNumber);
		Map<String, Object> envelope = new LinkedHashMap<>();
		envelope.put("success", false);
		envelope.put("tool", toolKey);
		envelope.put("errorType", "executor_failure");
		envelope.put("retryable", retriesLeft > 0);
		envelope.put("attempt", attemptsByTool.getOrDefault(toolKey, 0) + 1);
		envelope.put("failuresSoFar", nextFailureNumber);
		envelope.put("remainingRetriesForTool", retriesLeft);
		envelope.put("message", error);
		envelope.put("guidance",
			retriesLeft > 0
				? "Do not repeat the exact same call. Either fix the arguments, choose a different tool, or ask a focused clarification."
				: "Stop using this tool in this run. Choose a different tool or produce a final answer that explains the block.");
		String text = "Tool '" + toolKey + "' failed: " + error + " "
			+ (retriesLeft > 0
				? "You may retry with corrected arguments or a different tool."
				: "Retry budget for this tool is exhausted in this run.");
		return new ToolExecutionResult(false, toJson(envelope), text, error);
	}

	String buildRuntimeGuidance() {
		if (recentFailures.isEmpty()) {
			return "";
		}
		StringBuilder builder = new StringBuilder();
		builder.append("RUNTIME RETRY STATE FOR THIS RUN:\n");
		builder.append("  - Tool failures are recoverable. Learn from the latest tool result and continue if a corrected next action is available.\n");
		builder.append("  - Never repeat the same tool with the same arguments inside this run.\n");
		builder.append("  - A tool may fail at most ").append(maxToolRetries + 1)
			.append(" times in this run before it is considered exhausted.\n");
		builder.append("  - If the retry budget for a tool is exhausted, stop using that tool and either choose a different tool or end honestly.\n");
		builder.append("Recent failed attempts:\n");
		for (FailureRecord record : recentFailures) {
			int retriesLeft = Math.max(0, maxToolRetries - record.failureNumber());
			builder.append("  - ").append(record.toolKey())
				.append(" failure #").append(record.failureNumber())
				.append(" — ").append(record.error());
			if (retriesLeft > 0) {
				builder.append(" (").append(retriesLeft).append(" retry slot(s) left after changing approach)");
			}
			else {
				builder.append(" (do not use this tool again in this run)");
			}
			builder.append("\n");
		}
		builder.append("\n");
		return builder.toString();
	}

	private ToolExecutionResult duplicateCallResult(String toolKey, int priorExactAttempts) {
		Map<String, Object> envelope = new LinkedHashMap<>();
		envelope.put("success", false);
		envelope.put("tool", toolKey);
		envelope.put("errorType", "duplicate_call");
		envelope.put("retryable", false);
		envelope.put("attemptsWithExactArguments", priorExactAttempts);
		envelope.put("guidance",
			"Do not repeat the same tool call. Change the arguments, choose another tool, ask a clarification, or finish.");
		String message = "You already called " + toolKey
			+ " with these exact arguments earlier in this run. Do not repeat it.";
		return new ToolExecutionResult(false, toJson(envelope), message, message);
	}

	private ToolExecutionResult retryBudgetExceededResult(String toolKey, int failureCount) {
		Map<String, Object> envelope = new LinkedHashMap<>();
		envelope.put("success", false);
		envelope.put("tool", toolKey);
		envelope.put("errorType", "retry_budget_exhausted");
		envelope.put("retryable", false);
		envelope.put("failuresSoFar", failureCount);
		envelope.put("guidance",
			"Do not use this tool again in this run. Choose another tool or produce a final answer that explains the block.");
		String message = "Tool '" + toolKey + "' has already failed " + failureCount
			+ " time(s) in this run. Stop using it and choose another approach.";
		return new ToolExecutionResult(false, toJson(envelope), message, message);
	}

	private String compactError(ToolExecutionResult result) {
		if (result.error() != null && !result.error().isBlank()) {
			return result.error().trim();
		}
		if (result.textSummary() != null && !result.textSummary().isBlank()) {
			return result.textSummary().trim();
		}
		if (result.structuredOutput() != null && !result.structuredOutput().isBlank()) {
			return result.structuredOutput().trim();
		}
		return "Tool returned an unspecified failure.";
	}

	private static String compactThrowable(Exception exception) {
		String message = exception.getMessage();
		if (message == null || message.isBlank()) {
			return exception.getClass().getSimpleName();
		}
		return exception.getClass().getSimpleName() + ": " + message.trim();
	}

	private String toJson(Map<String, Object> payload) {
		try {
			return objectMapper.writeValueAsString(payload);
		}
		catch (JsonProcessingException exception) {
			return "{\"success\":false,\"errorType\":\"serialization_failure\"}";
		}
	}

	record GuardDecision(boolean allowed, ToolExecutionResult syntheticResult) {
	}

	private record FailureRecord(String toolKey, int failureNumber, String error) {
	}
}
