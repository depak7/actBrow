package com.actbrow.actbrow.api.dto;

import java.time.Instant;
import java.util.List;

public record NavigationFlowResponse(
	String id,
	String assistantId,
	String name,
	String triggerPhrase,
	List<NavigationStep> steps,
	boolean enabled,
	Instant createdAt
) {
	public record NavigationStep(
		String action,
		String target,
		String description
	) {
	}
}
