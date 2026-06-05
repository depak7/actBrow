package com.actbrow.actbrow.api.dto;

import java.time.Instant;
import java.util.List;

public record ApiIntegrationResponse(
	String id,
	String assistantId,
	String name,
	String baseUrl,
	boolean allowCrossOrigin,
	List<String> toolKeys,
	Instant createdAt,
	Instant updatedAt
) {
}
