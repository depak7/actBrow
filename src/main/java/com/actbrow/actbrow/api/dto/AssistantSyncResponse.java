package com.actbrow.actbrow.api.dto;

import java.time.Instant;
import java.util.Map;

public record AssistantSyncResponse(
	Instant syncedAt,
	Map<String, SyncCounts> summary,
	String widgetKey,
	String embedSnippet
) {
	public record SyncCounts(int created, int updated) {
	}
}
