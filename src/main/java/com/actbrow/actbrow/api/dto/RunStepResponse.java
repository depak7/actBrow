package com.actbrow.actbrow.api.dto;

import java.time.Instant;

import com.actbrow.actbrow.model.RunStepType;

/**
 * A single agent step. {@code payload} is sanitised and length-capped by the service — it is never
 * the raw stored value.
 */
public record RunStepResponse(
	String id,
	int stepIndex,
	RunStepType type,
	String payload,
	Instant createdAt
) {
}
