package com.actbrow.actbrow.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.actbrow.actbrow.model.ToolType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Some models answer with pseudo-tool text instead of native tool calls. Recover that into a
 * {@link ToolCallDecision} so the run pipeline still dispatches client tools.
 */
public final class PlainTextToolCallRecovery {

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	/** {@code app.navigate{"path": "/x"}} or optional backticks. */
	private static final Pattern TOOL_LINE = Pattern.compile(
		"^\\s*`?([a-zA-Z][a-zA-Z0-9_.]*)`?\\s*(\\{[\\s\\S]*\\})\\s*$");

	private static final Pattern CODE_FENCE = Pattern.compile(
		"^```[a-zA-Z0-9]*\\s*([\\s\\S]*?)```\\s*$");

	private PlainTextToolCallRecovery() {
	}

	public static ToolCallDecision tryRecover(String content, List<ToolDescriptor> tools, ObjectMapper objectMapper) {
		if (content == null || content.isBlank()) {
			return null;
		}
		String trimmed = content.trim();
		Matcher fence = CODE_FENCE.matcher(trimmed);
		if (fence.matches()) {
			trimmed = fence.group(1).trim();
		}

		ToolCallDecision fromJson = tryRecoverJsonFunctionShape(trimmed, tools, objectMapper);
		if (fromJson != null) {
			return fromJson;
		}

		Matcher m = TOOL_LINE.matcher(trimmed);
		if (!m.matches()) {
			return null;
		}
		String toolKey = m.group(1);
		String json = m.group(2);
		ToolDescriptor tool = resolveToolByKey(toolKey, tools);
		if (tool == null) {
			return null;
		}
		try {
			Map<String, Object> args = objectMapper.readValue(json, MAP_TYPE);
			return toolCallDecision(tool, toolKey, args);
		}
		catch (JsonProcessingException exception) {
			return null;
		}
	}

	/**
	 * e.g. {@code {"type": "function", "name": "app.navigate", "parameters": {"path": "/profile"}}}.
	 */
	private static ToolCallDecision tryRecoverJsonFunctionShape(String trimmed, List<ToolDescriptor> tools,
		ObjectMapper objectMapper) {
		if (!trimmed.startsWith("{")) {
			return null;
		}
		try {
			Map<String, Object> root = objectMapper.readValue(trimmed, MAP_TYPE);
			Object type = root.get("type");
			if (type != null && !"function".equals(String.valueOf(type))) {
				return null;
			}
			Object nameObj = root.get("name");
			if (nameObj == null) {
				nameObj = root.get("function");
			}
			if (nameObj == null) {
				return null;
			}
			String toolKey = String.valueOf(nameObj).trim();
			if (toolKey.isEmpty()) {
				return null;
			}
			Map<String, Object> params = Map.of();
			Object p = root.get("parameters");
			if (p == null) {
				p = root.get("arguments");
			}
			if (p != null) {
				if (!(p instanceof Map)) {
					return null;
				}
				params = objectMapper.convertValue(p, MAP_TYPE);
			}
			ToolDescriptor tool = resolveToolByKey(toolKey, tools);
			if (tool == null) {
				return null;
			}
			return toolCallDecision(tool, toolKey, params);
		}
		catch (JsonProcessingException exception) {
			return null;
		}
	}

	private static ToolCallDecision toolCallDecision(ToolDescriptor tool, String toolKey, Map<String, Object> args) {
		String callId = "plain-text-" + UUID.randomUUID();
		return new ToolCallDecision("Recovered tool call from model text: " + toolKey,
			new ToolCall(callId, tool.id(), tool.key(), args == null ? new LinkedHashMap<>() : args));
	}

	private static ToolDescriptor resolveToolByKey(String toolKey, List<ToolDescriptor> tools) {
		ToolDescriptor tool = tools.stream()
			.filter(t -> t.key().equals(toolKey))
			.findFirst()
			.orElse(null);
		if (tool == null && "app.navigate".equals(toolKey)) {
			List<ToolDescriptor> dedicated = dedicatedClientNavigateTools(tools);
			if (dedicated.size() == 1) {
				tool = dedicated.get(0);
			}
		}
		return tool;
	}

	private static List<ToolDescriptor> dedicatedClientNavigateTools(List<ToolDescriptor> tools) {
		return tools.stream()
			.filter(t -> t.type() == ToolType.CLIENT
				&& "app.navigate".equals(t.executorRef())
				&& !"app.navigate".equals(t.key()))
			.filter(t -> {
				Map<String, Object> defs = t.defaultArguments();
				return defs != null && defs.get("path") != null
					&& !String.valueOf(defs.get("path")).isBlank();
			})
			.toList();
	}
}
