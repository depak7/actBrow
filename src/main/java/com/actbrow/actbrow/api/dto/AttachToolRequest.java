package com.actbrow.actbrow.api.dto;

import jakarta.validation.constraints.NotBlank;

public record AttachToolRequest(@NotBlank String toolId) {
}
