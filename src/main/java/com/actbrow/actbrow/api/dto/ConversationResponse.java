package com.actbrow.actbrow.api.dto;

import java.time.Instant;

public record ConversationResponse(
	String id,
	String assistantId,
	Instant createdAt
) {
}
