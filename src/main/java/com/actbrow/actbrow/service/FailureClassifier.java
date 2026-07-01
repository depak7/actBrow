package com.actbrow.actbrow.service;

import java.util.Locale;

import org.springframework.stereotype.Service;

import com.actbrow.actbrow.agent.ToolDescriptor;
import com.actbrow.actbrow.agent.ToolExecutionResult;
import com.actbrow.actbrow.model.FailureType;

/**
 * Maps a raw {@link ToolExecutionResult} to a structured {@link FailureType}. This is the single
 * source of truth for failure classification; {@link RunVerifier} and {@code RunPolicyEngine}
 * both build on it so verifier status and recovery policy stay consistent.
 */
@Service
public class FailureClassifier {

	public FailureType classify(ToolDescriptor tool, ToolExecutionResult result) {
		if (result == null) {
			return FailureType.UNKNOWN;
		}
		if (result.success()) {
			return FailureType.NONE;
		}

		String signal = ((result.error() == null ? "" : result.error()) + " "
			+ (result.textSummary() == null ? "" : result.textSummary())).toLowerCase(Locale.ROOT);

		// Order matters: exhaustion and auth are checked before the transient/repairable buckets.
		if (containsAny(signal, "already called", "retry budget", "do not use this tool again", "unknown tool")) {
			return FailureType.TOOL_EXHAUSTED;
		}
		if (containsAny(signal, "unauthorized", "forbidden", "authentication", "sign in", "permission")) {
			return FailureType.AUTH_ERROR;
		}
		if (containsAny(signal, "429", "rate limit")) {
			return FailureType.RATE_LIMITED;
		}
		if (containsAny(signal, "timed out", "timeout", "504")) {
			return FailureType.TIMEOUT;
		}
		if (containsAny(signal, "500", "502", "503")) {
			return FailureType.SERVER_ERROR;
		}
		if (containsAny(signal, "not found", "404")) {
			return FailureType.NOT_FOUND;
		}
		if (containsAny(signal, "conflict", "409")) {
			return FailureType.CONFLICT;
		}
		if (containsAny(signal, "validation", "schema", "bad request", "missing", "400", "invalid")) {
			return FailureType.INVALID_ARGUMENTS;
		}
		if (tool != null
			&& ToolCatalogPolicies.executesAsClientPendingTool(tool.type(), tool.executorRef())
			&& containsAny(signal, "could not complete", "did not receive any data")) {
			return FailureType.CLIENT_INCOMPLETE;
		}
		return FailureType.UNKNOWN;
	}

	private static boolean containsAny(String value, String... needles) {
		for (String needle : needles) {
			if (value.contains(needle)) {
				return true;
			}
		}
		return false;
	}
}
