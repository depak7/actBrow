package com.actbrow.actbrow.api.dto;

import java.time.Instant;

/**
 * Mirrors {@link com.actbrow.actbrow.model.RunTraceEntity}. Only written when a run reaches a
 * terminal state, so it is absent (null) for runs still in flight.
 */
public record TraceResponse(
	String id,
	String runId,
	String conversationId,
	String assistantId,
	String promptVersion,
	String toolsetVersion,
	String planningOutcomes,
	String verifierDecisions,
	int executionAttempts,
	int toolCallCount,
	int observeCount,
	int screenshotCount,
	long clientToolWaitMs,
	String finalOutcome,
	long latencyMs,
	Instant createdAt
) {
}
