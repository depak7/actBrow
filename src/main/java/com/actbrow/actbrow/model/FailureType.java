package com.actbrow.actbrow.model;

/**
 * Structured classification of a tool outcome, produced by {@code FailureClassifier} and carried on
 * the verifier decision. Lets {@code RunPolicyEngine} choose a deterministic recovery path instead
 * of relying on prompt behavior alone.
 */
public enum FailureType {

	/** Tool succeeded — no failure. */
	NONE,

	/** Arguments/schema were wrong; a repaired retry may succeed. */
	INVALID_ARGUMENTS,

	/** Authentication/authorization is required — needs the user or operator. */
	AUTH_ERROR,

	/** Provider throttled the request (HTTP 429 / rate limit). Transient. */
	RATE_LIMITED,

	/** Request timed out. Transient. */
	TIMEOUT,

	/** Target resource does not exist (HTTP 404 / not found). */
	NOT_FOUND,

	/** State conflict (HTTP 409 / conflict). */
	CONFLICT,

	/** Upstream server error (HTTP 5xx). Transient. */
	SERVER_ERROR,

	/** This tool has hit its retry budget or was flagged unusable for the run. */
	TOOL_EXHAUSTED,

	/** A client/browser tool did not complete and likely needs the user to retry. */
	CLIENT_INCOMPLETE,

	/** Failure without a clear automatic recovery path. */
	UNKNOWN
}
