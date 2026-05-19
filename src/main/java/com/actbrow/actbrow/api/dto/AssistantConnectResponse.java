package com.actbrow.actbrow.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record AssistantConnectResponse(
	String assistantId,
	String assistantName,
	String baseUrl,
	String setupKey,
	String widgetKey,
	String setupPrompt,
	Instant lastSyncedAt,
	Map<String, Object> lastSyncSummary,
	List<String> allowedOrigins,
	String embedSnippet
) {
}
