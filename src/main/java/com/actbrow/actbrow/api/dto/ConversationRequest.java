package com.actbrow.actbrow.api.dto;

import jakarta.validation.constraints.NotBlank;

public record ConversationRequest(@NotBlank String assistantId) {
}
