package com.actbrow.actbrow.service;

import org.springframework.stereotype.Service;

import com.actbrow.actbrow.model.FailureType;

/**
 * Turns a {@link FailureType} into a deterministic recovery {@link PolicyAction}. Recovery becomes
 * code-driven rather than purely prompt-driven: the same failure class always maps to the same
 * next action, and different classes (auth vs rate-limit vs exhaustion) take different paths.
 */
@Service
public class RunPolicyEngine {

	public enum PolicyAction {
		/** Tool succeeded — keep going. */
		CONTINUE,
		/** Repairable/transient failure — retry, ideally with corrected arguments. */
		RETRY_WITH_REPAIRED_ARGUMENTS,
		/** This tool is exhausted but another tool could achieve the goal. */
		SWITCH_TOOL,
		/** The model should ask the user a focused clarifying question. */
		ASK_CLARIFICATION,
		/** Progress is blocked on the user/operator (e.g. auth). */
		REQUIRE_USER_INTERVENTION,
		/** No automatic recovery — stop and explain honestly. */
		STOP_WITH_EXPLANATION
	}

	public record PolicyDecision(PolicyAction action, FailureType failureType, String rationale) {
	}

	/**
	 * @param failureType         structured failure classification for the latest tool result
	 * @param alternativesAvailable whether another tool is available to try a different approach
	 */
	public PolicyDecision decide(FailureType failureType, boolean alternativesAvailable) {
		FailureType type = failureType == null ? FailureType.UNKNOWN : failureType;
		return switch (type) {
			case NONE -> new PolicyDecision(PolicyAction.CONTINUE, type,
				"Tool succeeded; continue the plan.");
			case AUTH_ERROR -> new PolicyDecision(PolicyAction.REQUIRE_USER_INTERVENTION, type,
				"Authentication/permission required; the user or operator must act before continuing.");
			case CLIENT_INCOMPLETE -> new PolicyDecision(PolicyAction.REQUIRE_USER_INTERVENTION, type,
				"Client-side tool did not complete; the user likely needs to retry or continue.");
			case INVALID_ARGUMENTS, NOT_FOUND, CONFLICT -> new PolicyDecision(
				PolicyAction.RETRY_WITH_REPAIRED_ARGUMENTS, type,
				"Failure looks repairable; retry with corrected arguments or a refined target.");
			case RATE_LIMITED, TIMEOUT, SERVER_ERROR -> new PolicyDecision(
				PolicyAction.RETRY_WITH_REPAIRED_ARGUMENTS, type,
				"Transient upstream failure; a retry (or an alternative tool) may succeed.");
			case TOOL_EXHAUSTED -> alternativesAvailable
				? new PolicyDecision(PolicyAction.SWITCH_TOOL, type,
					"This tool is exhausted for the run; switch to a different tool.")
				: new PolicyDecision(PolicyAction.STOP_WITH_EXPLANATION, type,
					"This tool is exhausted and no alternative is available; stop and explain.");
			case UNKNOWN -> alternativesAvailable
				? new PolicyDecision(PolicyAction.SWITCH_TOOL, type,
					"No clear recovery for this failure; try a different tool.")
				: new PolicyDecision(PolicyAction.STOP_WITH_EXPLANATION, type,
					"No clear recovery and no alternative tool; stop and explain honestly.");
		};
	}
}
