package com.actbrow.actbrow.api.dto;

import java.time.Instant;
import java.util.List;

public record InsightsResponse(
	String assistantId,
	long conversationCount,
	long runCount,
	long completedRuns,
	long failedRuns,
	long inProgressRuns,
	double successRate,
	List<IntentCount> topIntents,
	List<ToolFailureCount> failedTools,
	List<RecentFailure> recentFailures
) {
	public record IntentCount(String text, long count) {
	}

	public record ToolFailureCount(String toolKey, long count) {
	}

	public record RecentFailure(String runId, String error, Instant createdAt) {
	}
}
