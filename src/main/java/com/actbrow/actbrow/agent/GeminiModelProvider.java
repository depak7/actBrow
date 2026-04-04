package com.actbrow.actbrow.agent;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.actbrow.actbrow.config.GeminiProperties;
import com.actbrow.actbrow.model.ConversationMessageEntity;
import com.actbrow.actbrow.model.ConversationMessageRole;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class GeminiModelProvider implements ModelProvider {

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private final RestTemplate restTemplate;
	private final ObjectMapper objectMapper;
	private final GeminiProperties properties;

	public GeminiModelProvider(ObjectMapper objectMapper, GeminiProperties properties) {
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
		return "gemini";
	}

	@Override
	public ModelDecision decideNextStep(String model, String systemPrompt, List<ConversationMessageEntity> messages,
		List<ToolDescriptor> tools, int stepIndex) {
		if (properties.apiKey() == null || properties.apiKey().isBlank()) {
			throw new IllegalArgumentException("Gemini API key is not configured");
		}

		String resolvedModel = model == null || model.isBlank() ? properties.defaultModel() : model;
		URI uri = UriComponentsBuilder.fromUriString(trimTrailingSlash(properties.baseUrl()))
			.path("/models/{model}:generateContent")
			.queryParam("key", properties.apiKey())
			.buildAndExpand(resolvedModel)
			.encode()
			.toUri();

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);

		String responseBody;
		try {
			responseBody = restTemplate.exchange(
				uri,
				HttpMethod.POST,
				new HttpEntity<>(buildRequest(systemPrompt, messages, tools, stepIndex), headers),
				String.class).getBody();
		}
		catch (HttpStatusCodeException exception) {
			throw new IllegalArgumentException(
				"Gemini request failed with status %s: %s".formatted(exception.getStatusCode(),
					exception.getResponseBodyAsString()),
				exception);
		}

		try {
			return parseDecision(responseBody, tools);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalArgumentException("Unable to parse Gemini response", exception);
		}
	}

	private static String trimTrailingSlash(String base) {
		return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
	}

	private static Duration resolveTimeout(GeminiProperties properties) {
		return properties.requestTimeout() == null ? Duration.ofSeconds(20) : properties.requestTimeout();
	}

	private Map<String, Object> buildRequest(String systemPrompt, List<ConversationMessageEntity> messages,
		List<ToolDescriptor> tools, int stepIndex) {
		Map<String, Object> request = new LinkedHashMap<>();
		request.put("contents", buildContents(messages));
		request.put("generationConfig", Map.of("temperature", 0));
		if (systemPrompt != null && !systemPrompt.isBlank()) {
			request.put("systemInstruction", Map.of("parts", List.of(Map.of("text", buildSystemInstruction(systemPrompt)))));
		}
		else {
			request.put("systemInstruction", Map.of("parts", List.of(Map.of("text", buildSystemInstruction(null)))));
		}
		if (!tools.isEmpty()) {
			request.put("tools", List.of(Map.of("functionDeclarations", buildFunctionDeclarations(tools))));
			if (stepIndex == 0) {
				request.put("toolConfig", Map.of("functionCallingConfig", Map.of("mode", "AUTO")));
			}
		}
		return request;
	}

	private String buildSystemInstruction(String systemPrompt) {
		StringBuilder builder = new StringBuilder();
		if (systemPrompt != null && !systemPrompt.isBlank()) {
			builder.append(systemPrompt).append("\n\n");
		}
		builder.append("You are the backend decision engine for an embedded SaaS assistant. ");
		builder.append("If a provided tool is required, return exactly one function call. ");
		builder.append("If no tool is required, return a concise final answer in plain text. ");
		builder.append("Use only the declared functions. Do not invent tool names. ");
		builder.append("Do not answer with plain text that mimics a tool call ");
		builder.append("(e.g. app.navigate{...} or {\"type\":\"function\",\"name\":...}); use a real functionCall. ");
		builder.append("When several navigation tools exist, prefer the one whose description matches the request. ");
		builder.append("After a tool result appears in the conversation, use it to continue toward a final answer.");
		return builder.toString();
	}

	private List<Map<String, Object>> buildContents(List<ConversationMessageEntity> messages) {
		List<Map<String, Object>> contents = new ArrayList<>();
		for (ConversationMessageEntity message : messages) {
			String role = message.getRole() == ConversationMessageRole.ASSISTANT ? "model" : "user";
			String content = message.getRole() == ConversationMessageRole.TOOL
				? "Tool result observed: " + message.getContent()
				: message.getContent();
			contents.add(Map.of(
				"role", role,
				"parts", List.of(Map.of("text", content))));
		}
		return contents;
	}

	private List<Map<String, Object>> buildFunctionDeclarations(List<ToolDescriptor> tools) {
		return tools.stream()
			.map(tool -> {
				Map<String, Object> decl = new LinkedHashMap<>();
				decl.put("name", tool.key());
				decl.put("description", ModelToolPresentation.descriptionForModel(tool, objectMapper));
				decl.put("parameters", parseSchema(tool.inputSchema()));
				return decl;
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
		JsonNode candidate = root.path("candidates").path(0);
		if (candidate.isMissingNode()) {
			throw new IllegalArgumentException("Gemini returned no candidates");
		}

		JsonNode parts = candidate.path("content").path("parts");
		if (!parts.isArray() || parts.isEmpty()) {
			throw new IllegalArgumentException("Gemini returned no content parts");
		}

		for (JsonNode part : parts) {
			JsonNode functionCall = part.get("functionCall");
			if (functionCall != null && !functionCall.isNull()) {
				String toolKey = functionCall.path("name").asText();
				ToolDescriptor tool = tools.stream()
					.filter(item -> item.key().equals(toolKey))
					.findFirst()
					.orElseThrow(() -> new IllegalArgumentException("Gemini requested unknown tool: " + toolKey));
				Map<String, Object> arguments = objectMapper.convertValue(functionCall.path("args"), MAP_TYPE);
				return new ToolCallDecision("Gemini requested tool " + toolKey,
					new ToolCall(UUID.randomUUID().toString(), tool.id(), tool.key(), arguments));
			}
		}

		StringBuilder text = new StringBuilder();
		for (JsonNode part : parts) {
			JsonNode textNode = part.get("text");
			if (textNode != null && !textNode.isNull() && !textNode.asText().isBlank()) {
				if (!text.isEmpty()) {
					text.append('\n');
				}
				text.append(textNode.asText());
			}
		}
		if (text.isEmpty()) {
			throw new IllegalArgumentException("Gemini returned neither text nor function call");
		}
		String joined = text.toString();
		ToolCallDecision recovered = PlainTextToolCallRecovery.tryRecover(joined, tools, objectMapper);
		if (recovered != null) {
			return recovered;
		}
		return new FinalResponseDecision(joined);
	}
}
