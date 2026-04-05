package com.actbrow.actbrow.agent;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.actbrow.actbrow.config.OllamaProperties;
import com.actbrow.actbrow.model.ConversationMessageEntity;
import com.actbrow.actbrow.model.ConversationMessageRole;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class OllamaModelProvider implements ModelProvider {

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private final RestTemplate restTemplate;
	private final ObjectMapper objectMapper;
	private final OllamaProperties properties;

	public OllamaModelProvider(ObjectMapper objectMapper, OllamaProperties properties) {
		this.objectMapper = objectMapper;
		this.properties = properties;
		this.restTemplate = createRestTemplate(resolveTimeout(properties));
	}

	private static RestTemplate createRestTemplate(Duration readTimeout) {
		HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
		factory.setReadTimeout(readTimeout);
		return new RestTemplate(factory);
	}

	@Override
	public String providerKey() {
		return "ollama";
	}

	@Override
	public ModelDecision decideNextStep(String model, String systemPrompt, List<ConversationMessageEntity> messages,
		List<ToolDescriptor> tools, int stepIndex) {
		String resolvedModel = model == null || model.isBlank() ? properties.defaultModel() : model;
		String url = UriComponentsBuilder.fromUriString(trimTrailingSlash(properties.baseUrl()))
			.path("/v1/chat/completions")
			.build()
			.toUriString();

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		if (properties.apiKey() != null && !properties.apiKey().isBlank()) {
			headers.setBearerAuth(properties.apiKey());
		}

		String responseBody;
		try {
			responseBody = chatCompletion(url, headers, resolvedModel, systemPrompt, messages, tools);
		}
		catch (HttpStatusCodeException exception) {
			throw new IllegalArgumentException(
				"Ollama request failed with status %s: %s".formatted(exception.getStatusCode(),
					exception.getResponseBodyAsString()),
				exception);
		}

		try {
			return parseDecision(responseBody, tools);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalArgumentException("Unable to parse Ollama response", exception);
		}
	}

	private String chatCompletion(String url, HttpHeaders headers, String model, String systemPrompt,
		List<ConversationMessageEntity> messages, List<ToolDescriptor> tools) {
		return restTemplate.exchange(
			url,
			HttpMethod.POST,
			new HttpEntity<>(buildRequest(model, systemPrompt, messages, tools), headers),
			String.class).getBody();
	}

	private static String trimTrailingSlash(String base) {
		return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
	}

	private static Duration resolveTimeout(OllamaProperties properties) {
		return properties.requestTimeout() == null ? Duration.ofSeconds(60) : properties.requestTimeout();
	}

	private Map<String, Object> buildRequest(String model, String systemPrompt, List<ConversationMessageEntity> messages,
		List<ToolDescriptor> tools) {
		Map<String, Object> request = new LinkedHashMap<>();
		request.put("model", model);
		request.put("messages", buildMessages(systemPrompt, messages));
		request.put("temperature", 0);
		if (!tools.isEmpty()) {
			request.put("tools", buildTools(tools));
			request.put("tool_choice", "auto");
		}
		return request;
	}

	private List<Map<String, Object>> buildMessages(String systemPrompt, List<ConversationMessageEntity> messages) {
		List<Map<String, Object>> result = new ArrayList<>();

		String systemContent = buildSystemPrompt(systemPrompt);
		result.add(Map.of("role", "system", "content", systemContent));

		for (ConversationMessageEntity message : messages) {
			String role = message.getRole().name().toLowerCase();
			String content = message.getRole() == ConversationMessageRole.TOOL
				? "Tool result: " + message.getContent()
				: message.getContent();

			if (message.getRole() == ConversationMessageRole.TOOL) {
				result.add(Map.of("role", "tool", "content", content, "tool_call_id", message.getId().toString()));
			}
			else {
				result.add(Map.of("role", role, "content", content));
			}
		}
		return result;
	}

	private String buildSystemPrompt(String systemPrompt) {
		StringBuilder builder = new StringBuilder();
		if (systemPrompt != null && !systemPrompt.isBlank()) {
			builder.append(systemPrompt).append("\n\n");
		}
		builder.append("You are the backend decision engine for an embedded SaaS assistant. ");
		builder.append("If a listed tool is required, emit it as a native tool/function call. ");
		builder.append("If no tool is required, reply with a concise final answer in plain text only. ");
		builder.append("Use only the declared function names. Do not invent tools. ");
		builder.append("When several navigation tools exist, prefer the specific tool whose description matches the request. ");
		builder.append("After a tool result appears in the conversation, use it to continue toward a final answer.");
		return builder.toString();
	}

	private List<Map<String, Object>> buildTools(List<ToolDescriptor> tools) {
		return tools.stream()
			.map(tool -> {
				Map<String, Object> function = new LinkedHashMap<>();
				function.put("name", tool.key());
				function.put("description", ModelToolPresentation.descriptionForModel(tool, objectMapper));
				function.put("parameters", parseSchema(tool.inputSchema()));
				Map<String, Object> spec = new LinkedHashMap<>();
				spec.put("type", "function");
				spec.put("function", function);
				return spec;
			})
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
		JsonNode choice = root.path("choices").path(0);
		if (choice.isMissingNode()) {
			throw new IllegalArgumentException("Ollama returned no choices");
		}

		JsonNode message = choice.path("message");
		JsonNode toolCalls = message.get("tool_calls");

		if (toolCalls != null && !toolCalls.isNull() && toolCalls.isArray() && !toolCalls.isEmpty()) {
			JsonNode firstCall = toolCalls.path(0);
			String toolKey = firstCall.path("function").path("name").asText();
			ToolDescriptor tool = tools.stream()
				.filter(item -> item.key().equals(toolKey))
				.findFirst()
				.orElseThrow(() -> new IllegalArgumentException("Ollama requested unknown tool: " + toolKey));

			String argumentsJson = firstCall.path("function").path("arguments").asText();
			Map<String, Object> arguments = objectMapper.readValue(argumentsJson, MAP_TYPE);

			String callId = firstCall.path("id").asText();
			return new ToolCallDecision("Ollama requested tool " + toolKey,
				new ToolCall(callId, tool.id(), tool.key(), arguments));
		}

		String content = message.path("content").asText(null);
		if (content == null || content.isBlank()) {
			throw new IllegalArgumentException("Ollama returned neither text nor function call");
		}
		ToolCallDecision recovered = PlainTextToolCallRecovery.tryRecover(content, tools, objectMapper);
		if (recovered != null) {
			return recovered;
		}
		return new FinalResponseDecision(content);
	}
}