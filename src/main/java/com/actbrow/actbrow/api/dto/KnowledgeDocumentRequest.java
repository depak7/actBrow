package com.actbrow.actbrow.api.dto;

import jakarta.validation.constraints.NotBlank;

public record KnowledgeDocumentRequest(
	@NotBlank String title,
	@NotBlank String content,
	String source,
	boolean enabled
) {
}
