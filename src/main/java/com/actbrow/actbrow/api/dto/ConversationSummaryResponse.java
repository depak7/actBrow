package com.actbrow.actbrow.api.dto;

import java.time.Instant;

public record ConversationSummaryResponse(
	String id,
	String assistantId,
	String assistantName,
	Instant createdAt,
	Instant lastMessageAt,
	long messageCount,
	String lastMessageRole,
	String lastMessagePreview
) {
}
