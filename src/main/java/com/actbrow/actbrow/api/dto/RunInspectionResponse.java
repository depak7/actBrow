package com.actbrow.actbrow.api.dto;

import java.time.Instant;
import java.util.List;

import com.actbrow.actbrow.model.RunStatus;

/**
 * Full "why did the agent do that?" view of one run: its outcome plus every recorded step in order,
 * and the evaluation trace when one exists.
 */
public record RunInspectionResponse(
	String runId,
	RunStatus status,
	int stepCount,
	String lastError,
	Instant createdAt,
	Instant completedAt,
	Long durationMs,
	List<RunStepResponse> steps,
	TraceResponse trace
) {
}
