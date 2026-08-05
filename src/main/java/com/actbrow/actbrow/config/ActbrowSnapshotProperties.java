package com.actbrow.actbrow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Controls how the SDK's page.screenshot tool captures the host page.
 *
 * <ul>
 *   <li>{@code text} (default): structure-first observe alias — interactive elements
 *       (ref/role/name), headings, and capped visibleText. Prefer {@code page.observe}
 *       for new callers; text mode keeps backward compatibility.</li>
 *   <li>{@code image}: SDK rasterizes the viewport via html2canvas and returns a base64
 *       PNG. Vision fallback only; the model provider must be vision-capable
 *       (Gemini, GPT-4o, Claude, etc.).</li>
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
