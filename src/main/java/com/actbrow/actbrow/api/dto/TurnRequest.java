package com.actbrow.actbrow.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.validation.constraints.NotBlank;

/**
 * @param content      User-visible message.
 * @param pageContext  Optional JSON snapshot from the browser (url, title, interactive elements with selectors).
 *                     When set, it is appended to the stored user message so the model can ground tool calls.
 */
public record TurnRequest(@NotBlank String content, JsonNode pageContext) {
}
