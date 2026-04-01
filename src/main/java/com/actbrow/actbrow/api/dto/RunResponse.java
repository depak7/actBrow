package com.actbrow.actbrow.api.dto;

import java.time.Instant;

import com.actbrow.actbrow.model.RunStatus;

public record RunResponse(
	String id,
	String conversationId,
	String assistantId,
	RunStatus status,
	int stepCount,
	String lastError,
	Instant createdAt,
	Instant completedAt
) {
}
