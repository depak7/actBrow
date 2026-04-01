package com.actbrow.actbrow.agent;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.actbrow.actbrow.config.SarvamProperties;
import com.actbrow.actbrow.model.ConversationMessageEntity;
import com.actbrow.actbrow.model.ConversationMessageRole;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class SarvamModelProvider implements ModelProvider {

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private final WebClient webClient;
	private final ObjectMapper objectMapper;
	private final SarvamProperties properties;

	public SarvamModelProvider(WebClient.Builder webClientBuilder, ObjectMapper objectMapper,
		SarvamProperties properties) {
		this.objectMapper = objectMapper;
		this.properties = properties;
		this.webClient = webClientBuilder.baseUrl(properties.baseUrl()).build();
	}

	@Override
	public String providerKey() {
		return "sarvam";
	}

	@Override
	public ModelDecision decideNextStep(String model, String systemPrompt, List<ConversationMessageEntity> messages,
		List<ToolDescriptor> tools, int stepIndex) {
		if (properties.apiKey() == null || properties.apiKey().isBlank()) {
			throw new IllegalArgumentException("Sarvam API key is not configured");
		}

		String resolvedModel = model == null || model.isBlank() ? properties.defaultModel() : model;
		String responseBody = webClient.post()
			.uri("/chat/completions")
			.header("api-subscription-key", properties.apiKey())
			.bodyValue(buildRequest(resolvedModel, systemPrompt, messages, tools))
			.retrieve()
			.bodyToMono(String.class)
			.timeout(resolveTimeout())
			.onErrorMap(WebClientResponseException.class, exception -> new IllegalArgumentException(
				"Sarvam request failed with status %s: %s".formatted(exception.getStatusCode(), exception.getResponseBodyAsString()),
				exception))
			.block();

		try {
			return parseDecision(responseBody, tools);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalArgumentException("Unable to parse Sarvam response", exception);
		}
	}

	private Duration resolveTimeout() {
		return properties.requestTimeout() == null ? Duration.ofSeconds(20) : properties.requestTimeout();
	}

	private Map<String, Object> buildRequest(String model, String systemPrompt, List<ConversationMessageEntity> messages,
		List<ToolDescriptor> tools) {
		Map<String, Object> request = new LinkedHashMap<>();
		request.put("model", model);
		request.put("temperature", 0);
		request.put("messages", buildMessages(systemPrompt, messages));
		if (!tools.isEmpty()) {
			request.put("tools", buildTools(tools));
			request.put("tool_choice", "auto");
		}
		return request;
	}

	private List<Map<String, Object>> buildMessages(String systemPrompt, List<ConversationMessageEntity> messages) {
		List<Map<String, Object>> payload = new java.util.ArrayList<>();
		payload.add(Map.of(
			"role", "system",
			"content", buildSystemInstruction(systemPrompt)));
		for (ConversationMessageEntity message : messages) {
			String role = switch (message.getRole()) {
			case ASSISTANT -> "assistant";
			case TOOL -> "tool";
			case USER -> "user";
			};
			if (message.getRole() == ConversationMessageRole.TOOL) {
				payload.add(Map.of(
					"role", role,
					"content", "Tool result observed: " + message.getContent()));
			}
			else {
				payload.add(Map.of(
					"role", role,
					"content", message.getContent()));
			}
		}
		return payload;
	}

	private String buildSystemInstruction(String systemPrompt) {
		StringBuilder builder = new StringBuilder();
		if (systemPrompt != null && !systemPrompt.isBlank()) {
			builder.append(systemPrompt).append("\n\n");
		}
		builder.append("You are the backend decision engine for an embedded SaaS assistant. ");
		builder.append("If a provided tool is required, return exactly one tool call. ");
		builder.append("If no tool is required, return a concise final answer in plain text. ");
		builder.append("Use only the declared tools. Do not invent tool names. ");
		builder.append("After a tool result appears in the conversation, use it to continue toward a final answer.");
		return builder.toString();
	}

	private List<Map<String, Object>> buildTools(List<ToolDescriptor> tools) {
		return tools.stream()
			.map(tool -> Map.of(
				"type", "function",
				"function", Map.of(
					"name", tool.key(),
					"description", tool.description(),
					"parameters", parseSchema(tool.inputSchema()))))
			.toList();
	}

	private Map<String, Object> parseSchema(String inputSchema) {
		try {
			return objectMapper.readValue(inputSchema, MAP_TYPE);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalArgumentException("Invalid stored tool schema", exception);
		}
	}

	private ModelDecision parseDecision(String responseBody, List<ToolDescriptor> tools) throws JsonProcessingException {
		JsonNode root = objectMapper.readTree(responseBody);
		JsonNode message = root.path("choices").path(0).path("message");
		if (message.isMissingNode()) {
			throw new IllegalArgumentException("Sarvam returned no choices");
		}

		JsonNode toolCalls = message.path("tool_calls");
		if (toolCalls.isArray() && !toolCalls.isEmpty()) {
			JsonNode toolCallNode = toolCalls.get(0);
			String toolKey = toolCallNode.path("function").path("name").asText();
			ToolDescriptor tool = tools.stream()
				.filter(item -> item.key().equals(toolKey))
				.findFirst()
				.orElseThrow(() -> new IllegalArgumentException("Sarvam requested unknown tool: " + toolKey));
			String argumentsJson = toolCallNode.path("function").path("arguments").asText("{}");
			Map<String, Object> arguments = objectMapper.readValue(argumentsJson, MAP_TYPE);
			return new ToolCallDecision("Sarvam requested tool " + toolKey,
				new ToolCall(UUID.randomUUID().toString(), tool.id(), tool.key(), arguments));
		}

		String content = message.path("content").asText("");
		if (content.isBlank()) {
			throw new IllegalArgumentException("Sarvam returned neither text nor tool call");
		}
		return new FinalResponseDecision(content);
	}
}
