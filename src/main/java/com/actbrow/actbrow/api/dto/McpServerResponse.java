package com.actbrow.actbrow.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record McpServerResponse(
	String id,
	String assistantId,
	String name,
	String serverUrl,
	/** Redacted metadata only — never raw secret values. */
	Map<String, Object> authHeaders,
	boolean enabled,
	List<String> toolKeys,
	Instant lastSyncedAt,
	Instant createdAt,
	Instant updatedAt
) {
}
