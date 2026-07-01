package com.actbrow.actbrow.service;

import org.springframework.stereotype.Service;

import com.actbrow.actbrow.agent.ToolDescriptor;
import com.actbrow.actbrow.agent.ToolExecutionResult;
import com.actbrow.actbrow.model.FailureType;

@Service
public class RunVerifier {

	private final FailureClassifier failureClassifier;

	public RunVerifier(FailureClassifier failureClassifier) {
		this.failureClassifier = failureClassifier;
	}

	public VerificationDecision verify(ToolDescriptor tool, ToolExecutionResult result) {
		FailureType failureType = failureClassifier.classify(tool, result);
		return switch (failureType) {
			case NONE -> new VerificationDecision(VerificationStatus.SUCCEEDED,
				"Tool completed successfully.", false, FailureType.NONE);
			case TOOL_EXHAUSTED -> new VerificationDecision(VerificationStatus.TERMINAL_FAILURE,
				"Tool path is exhausted for this run.", true, failureType);
			case AUTH_ERROR -> new VerificationDecision(VerificationStatus.NEEDS_USER_INPUT,
				"Tool requires user or operator intervention before continuing.", true, failureType);
			case CLIENT_INCOMPLETE -> new VerificationDecision(VerificationStatus.NEEDS_USER_INPUT,
				"Client-side tool did not complete and likely needs the user to retry or continue.", true, failureType);
			case INVALID_ARGUMENTS, NOT_FOUND, CONFLICT, RATE_LIMITED, TIMEOUT, SERVER_ERROR ->
				new VerificationDecision(VerificationStatus.RETRYABLE_FAILURE,
					"Tool failure looks repairable with a changed approach.", true, failureType);
			case UNKNOWN -> new VerificationDecision(VerificationStatus.TERMINAL_FAILURE,
				"Tool failed without a clear automatic recovery path.", true, FailureType.UNKNOWN);
		};
	}

	public record VerificationDecision(
		VerificationStatus status,
		String rationale,
		boolean yieldToPlanner,
		FailureType failureType
	) {
	}

	public enum VerificationStatus {
		SUCCEEDED,
		PARTIAL,
		RETRYABLE_FAILURE,
		TERMINAL_FAILURE,
		NEEDS_USER_INPUT
	}
}
