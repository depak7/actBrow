package com.actbrow.actbrow.api.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateAssistantRequest(
        @NotBlank String key,
        @NotBlank String name,
        String systemPrompt,
        @NotBlank String model,
        boolean usePredefinedFlows,
        String tenantId
) {
}
