package com.actbrow.actbrow.service;

import java.util.List;
import java.util.Map;

import com.actbrow.actbrow.agent.ToolDescriptor;
import com.actbrow.actbrow.model.SideEffectLevel;

/**
 * A behavioral contract for a tool, read from its metadata (Phase 6). Lets policy differentiate
 * read tools from write tools and require post-action verification for writes.
 *
 * <p>Recognised metadata keys: {@code retryable} (bool), {@code idempotent} (bool),
 * {@code sideEffectLevel} (READ|WRITE|DESTRUCTIVE), {@code verificationTool} (string),
 * {@code verificationMode} (string), {@code preconditions} (list/string),
 * {@code commonFailureModes} (list/string). Missing keys fall back to safe read-only defaults.
 */
public record ToolContract(
	boolean retryable,
	boolean idempotent,
	SideEffectLevel sideEffectLevel,
	String verificationTool,
	String verificationMode,
	List<String> preconditions,
	List<String> commonFailureModes
) {

	public static ToolContract from(ToolDescriptor tool) {
		Map<String, Object> metadata = tool == null || tool.metadata() == null ? Map.of() : tool.metadata();
		SideEffectLevel level = SideEffectLevel.fromMetadata(metadata.get("sideEffectLevel"));
		return new ToolContract(
			boolValue(metadata.get("retryable"), true),
			boolValue(metadata.get("idempotent"), level == SideEffectLevel.READ),
			level,
			stringOrNull(metadata.get("verificationTool")),
			stringOrNull(metadata.get("verificationMode")),
			stringList(metadata.get("preconditions")),
			stringList(metadata.get("commonFailureModes")));
	}

	public boolean isWrite() {
		return sideEffectLevel != SideEffectLevel.READ;
	}

	/** Writes should be verified after the fact; explicit verificationMode="none" opts out. */
	public boolean requiresPostVerification() {
		if (!isWrite()) {
			return false;
		}
		return verificationMode == null || !"none".equalsIgnoreCase(verificationMode.trim());
	}

	private static boolean boolValue(Object value, boolean fallback) {
		if (value instanceof Boolean bool) {
			return bool;
		}
		if (value != null) {
			return Boolean.parseBoolean(value.toString().trim());
		}
		return fallback;
	}

	private static String stringOrNull(Object value) {
		if (value == null) {
			return null;
		}
		String text = value.toString().trim();
		return text.isEmpty() ? null : text;
	}

	private static List<String> stringList(Object value) {
		if (value instanceof List<?> list) {
			return list.stream().filter(java.util.Objects::nonNull).map(Object::toString).toList();
		}
		if (value != null && !value.toString().isBlank()) {
			return List.of(value.toString().trim());
		}
		return List.of();
	}
}
