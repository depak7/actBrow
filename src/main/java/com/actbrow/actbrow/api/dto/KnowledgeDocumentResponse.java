package com.actbrow.actbrow.api.dto;

import java.time.Instant;

public record KnowledgeDocumentResponse(
	String id,
	String assistantId,
	String title,
	String content,
	String source,
	boolean enabled,
	Instant createdAt,
	Instant updatedAt
) {
}
