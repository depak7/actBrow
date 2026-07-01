package com.actbrow.actbrow.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class ClaudeCliService {

	private static final Pattern TOOL_CALL_PATTERN = Pattern.compile(
		"<tool_call>\\s*(\\{.*?\\})\\s*</tool_call>", Pattern.DOTALL);

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

	private final ObjectMapper objectMapper;

	public ClaudeCliService(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@SuppressWarnings("unchecked")
	public Map<String, Object> complete(Map<String, Object> request) {
		String requestedModel = (String) request.getOrDefault("model", "claude-sonnet-4-6");
		List<Map<String, Object>> messages = (List<Map<String, Object>>) request.getOrDefault("messages", List.of());
		List<Map<String, Object>> tools = (List<Map<String, Object>>) request.getOrDefault("tools", List.of());

		String systemPrompt = buildSystemPrompt(messages, tools);
		String conversationPrompt = buildConversationPrompt(messages);
		String model = resolveModel(requestedModel);

		String output = runClaude(conversationPrompt, systemPrompt, model);
		return buildResponse(output, requestedModel);
	}

	private String buildSystemPrompt(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
		StringBuilder sb = new StringBuilder();

		for (Map<String, Object> message : messages) {
			if ("system".equals(message.get("role"))) {
				Object content = message.get("content");
				if (content instanceof String s) {
					sb.append(s);
				}
				break;
			}
		}

		if (!tools.isEmpty()) {
			if (!sb.isEmpty()) {
				sb.append("\n\n");
			}
			sb.append("You have access to the following tools. ");
			sb.append("When you want to call a tool, output ONLY this — nothing else before or after:\n");
			sb.append("<tool_call>\n");
			sb.append("{\"name\": \"tool_name\", \"id\": \"call_1\", \"arguments\": {\"param\": \"value\"}}\n");
			sb.append("</tool_call>\n\n");
			sb.append("For multiple tool calls, use multiple <tool_call> blocks.\n");
			sb.append("If no tool is needed, reply in plain text.\n\n");
			sb.append("Available tools:\n");
			try {
				sb.append(objectMapper.writeValueAsString(tools));
			}
			catch (JsonProcessingException e) {
				sb.append(tools.toString());
			}
		}

		return sb.toString();
	}

	@SuppressWarnings("unchecked")
	private String buildConversationPrompt(List<Map<String, Object>> messages) {
		StringBuilder sb = new StringBuilder();

		for (Map<String, Object> message : messages) {
			String role = (String) message.get("role");
			if ("system".equals(role)) {
				continue;
			}

			if ("user".equals(role)) {
				sb.append("Human: ").append(contentAsString(message.get("content"))).append("\n");
			}
			else if ("assistant".equals(role)) {
				List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) message.get("tool_calls");
				if (toolCalls != null && !toolCalls.isEmpty()) {
					List<String> names = toolCalls.stream()
						.map(tc -> {
							Map<String, Object> fn = (Map<String, Object>) tc.get("function");
							return fn != null ? (String) fn.get("name") : "unknown";
						})
						.toList();
					sb.append("Assistant: [called tools: ").append(String.join(", ", names)).append("]\n");
				}
				else {
					String text = contentAsString(message.get("content"));
					if (text != null && !text.isBlank()) {
						sb.append("Assistant: ").append(text).append("\n");
					}
				}
			}
			else if ("tool".equals(role)) {
				String toolCallId = (String) message.getOrDefault("tool_call_id", "");
				sb.append("Tool result (").append(toolCallId).append("): ")
					.append(contentAsString(message.get("content"))).append("\n");
			}
		}

		sb.append("\nAssistant:");
		return sb.toString();
	}

	private String contentAsString(Object content) {
		if (content == null) {
			return "";
		}
		if (content instanceof String s) {
			return s;
		}
		// content can be a list of content blocks (OpenAI vision format)
		try {
			return objectMapper.writeValueAsString(content);
		}
		catch (JsonProcessingException e) {
			return content.toString();
		}
	}

	protected String runClaude(String conversationPrompt, String systemPrompt, String model) {
		List<String> cmd = new ArrayList<>();
		cmd.add("claude");
		cmd.add("-p");
		cmd.add(conversationPrompt);

		if (systemPrompt != null && !systemPrompt.isBlank()) {
			cmd.add("--system-prompt");
			cmd.add(systemPrompt);
		}

		if (model != null && !model.isBlank()) {
			cmd.add("--model");
			cmd.add(model);
		}

		// Disable Claude Code's built-in tools (we handle tool dispatch ourselves)
		// and skip session persistence (stateless per-request invocations).
		cmd.add("--tools");
		cmd.add("--output-format text");
		cmd.add("--no-session-persistence");
		cmd.add("--no-session-persistence");

		try {
			ProcessBuilder pb = new ProcessBuilder(cmd);
			pb.redirectErrorStream(false);
			pb.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));
			Process process = pb.start();

			boolean finished = process.waitFor(120, TimeUnit.SECONDS);
			if (!finished) {
				process.destroyForcibly();
				throw new RuntimeException("claude CLI timed out after 120 seconds");
			}

			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
			int exitCode = process.exitValue();
			if (exitCode != 0) {
				String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
				throw new RuntimeException("claude CLI exited with code " + exitCode + ": " + err);
			}
			return output;
		}
		catch (IOException | InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("Failed to run claude CLI: " + e.getMessage(), e);
		}
	}

	private Map<String, Object> buildResponse(String output, String model) {
		List<Map<String, Object>> toolCalls = extractToolCalls(output);

		Map<String, Object> message = new LinkedHashMap<>();
		message.put("role", "assistant");

		String finishReason;
		if (!toolCalls.isEmpty()) {
			message.put("content", null);
			message.put("tool_calls", toolCalls);
			finishReason = "tool_calls";
		}
		else {
			message.put("content", output);
			finishReason = "stop";
		}

		Map<String, Object> choice = new LinkedHashMap<>();
		choice.put("index", 0);
		choice.put("message", message);
		choice.put("finish_reason", finishReason);

		Map<String, Object> usage = new LinkedHashMap<>();
		usage.put("prompt_tokens", 0);
		usage.put("completion_tokens", 0);
		usage.put("total_tokens", 0);

		Map<String, Object> response = new LinkedHashMap<>();
		response.put("id", "chatcmpl-local-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
		response.put("object", "chat.completion");
		response.put("created", Instant.now().getEpochSecond());
		response.put("model", model);
		response.put("choices", List.of(choice));
		response.put("usage", usage);

		return response;
	}

	private List<Map<String, Object>> extractToolCalls(String output) {
		List<Map<String, Object>> calls = new ArrayList<>();
		Matcher matcher = TOOL_CALL_PATTERN.matcher(output);

		while (matcher.find()) {
			String json = matcher.group(1).trim();
			try {
				JsonNode node = objectMapper.readTree(json);
				String name = node.path("name").asText();
				String id = node.path("id").asText();
				if (id.isBlank()) {
					id = "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
				}

				JsonNode argsNode = node.path("arguments");
				String arguments = argsNode.isMissingNode() ? "{}" : objectMapper.writeValueAsString(argsNode);

				Map<String, Object> fn = new LinkedHashMap<>();
				fn.put("name", name);
				fn.put("arguments", arguments);

				Map<String, Object> call = new LinkedHashMap<>();
				call.put("id", id);
				call.put("type", "function");
				call.put("function", fn);

				calls.add(call);
			}
			catch (JsonProcessingException e) {
				// skip malformed tool call blocks
			}
		}

		return calls;
	}

	private static String resolveModel(String model) {
		if (model == null) {
			return "claude-sonnet-4-6";
		}
		int idx = model.indexOf(':');
		if (idx >= 0) {
			String prefix = model.substring(0, idx).trim().toLowerCase();
			if ("gemini".equals(prefix) || "openai".equals(prefix)) {
				return model.substring(idx + 1).trim();
			}
		}
		return model;
	}

}
