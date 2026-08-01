package com.actbrow.actbrow.api.dto;

import java.time.Instant;

import com.actbrow.actbrow.model.RunStatus;

/**
 * One row in the run list of a conversation. Carries the counters a dashboard needs to spot the
 * interesting run (slow, errored, tool-heavy) without fetching every run's steps.
 */
public record RunSummaryResponse(
	String id,
	RunStatus status,
	int stepCount,
	String lastError,
	Instant createdAt,
	Instant completedAt,
	Long durationMs,
	int toolCallCount,
	int failedToolCount
) {
}
