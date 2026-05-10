package com.actbrow.actbrow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Controls how the SDK's page.screenshot tool captures the host page.
 *
 * <ul>
 *   <li>{@code text} (default): innerText + semantic-attribute labels. Works on any
 *       text LLM (DeepSeek, GPT, etc.).</li>
 *   <li>{@code image}: SDK rasterizes the viewport via html2canvas and returns a base64
 *       PNG. The model provider must be vision-capable (GPT-4o/Claude/Gemini, etc.) —
 *       DeepSeek's hosted chat-completions API does NOT accept image content and will
 *       400 the request.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "actbrow.snapshot")
public record ActbrowSnapshotProperties(String mode) {

	public ActbrowSnapshotProperties {
		if (mode == null || mode.isBlank()) {
			mode = "text";
		}
		mode = mode.trim().toLowerCase();
		if (!mode.equals("text") && !mode.equals("image")) {
			throw new IllegalArgumentException("actbrow.snapshot.mode must be 'text' or 'image', got: " + mode);
		}
	}
}
