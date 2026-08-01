package com.actbrow.actbrow.api.dto;

import java.util.List;

public record SafetyStatusResponse(
	String assistantId,
	boolean toolsEnabled,
	boolean shadowMode,
	List<CircuitResponse> circuits
) {
}
