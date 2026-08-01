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
		SideEffectLevel level = inferSideEffectLevel(tool);
		return new ToolContract(
			boolValue(metadata.get("retryable"), true),
			boolValue(metadata.get("idempotent"), level == SideEffectLevel.READ),
			level,
			stringOrNull(metadata.get("verificationTool")),
			stringOrNull(metadata.get("verificationMode")),
			stringList(metadata.get("preconditions")),
			stringList(metadata.get("commonFailureModes")));
	}

	/**
	 * Single source of truth for how consequential a tool is, so policy and tool disclosure can never
	 * disagree about the same tool.
	 *
	 * <p>Explicit {@code metadata.sideEffectLevel} always wins. Otherwise the level is inferred, and
	 * the inference is deliberately pessimistic: an operator-synced HTTP or MCP tool that forgot the
	 * annotation is treated as a WRITE rather than a READ. Guessing READ would silently exempt real
	 * writes from shadow mode and post-action verification — the failure that actually hurts.
	 */
	public static SideEffectLevel inferSideEffectLevel(ToolDescriptor tool) {
		Map<String, Object> metadata = tool == null || tool.metadata() == null ? Map.of() : tool.metadata();
		Object declared = metadata.get("sideEffectLevel");
		if (declared != null) {
			return SideEffectLevel.fromMetadata(declared);
		}
		Object method = metadata.get("method");
		if (method != null) {
			return switch (method.toString().trim().toUpperCase(java.util.Locale.ROOT)) {
				case "GET", "HEAD", "OPTIONS" -> SideEffectLevel.READ;
				case "DELETE" -> SideEffectLevel.DESTRUCTIVE;
				default -> SideEffectLevel.WRITE;
			};
		}
		if (tool != null && (tool.type() == com.actbrow.actbrow.model.ToolType.SERVER_HTTP
			|| tool.type() == com.actbrow.actbrow.model.ToolType.MCP)) {
			return SideEffectLevel.WRITE;
		}
		// Built-in observation and client tools (path.find, page.screenshot, app.navigate) only read.
		return SideEffectLevel.READ;
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
