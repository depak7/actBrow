package com.actbrow.actbrow.api.dto;

import java.util.List;

public record ActivationStatusResponse(
	String assistantId,
	String assistantName,
	int completedSteps,
	int totalSteps,
	boolean ready,
	List<ActivationStep> steps,
	String embedSnippet,
	String magicLinkExample
) {
	public record ActivationStep(
		String id,
		String title,
		String description,
		boolean done,
		String href
	) {
	}
}
