package com.actbrow.actbrow.api.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record NavigationFlowRequest(
	@NotBlank String name,
	@NotBlank String triggerPhrase,
	@NotNull List<NavigationStep> steps,
	boolean enabled
) {
	public record NavigationStep(
		@NotBlank String action,
		@NotBlank String target,
		String description
	) {
	}
}
