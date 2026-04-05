package com.actbrow.actbrow.api.dto;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;

/**
 * @param content      User-visible message.
 * @param pageContext  Optional JSON snapshot from the browser (url, title, interactive elements with selectors).
 *                     When set, it is appended to the stored user message so the model can ground tool calls.
 *                     JSON object from the client; bound as a map for reliable request decoding.
 */
public record TurnRequest(@NotBlank String content, Map<String, Object> pageContext) {
}
