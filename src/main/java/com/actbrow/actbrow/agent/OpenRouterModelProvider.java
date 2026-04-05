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

import com.actbrow.actbrow.config.OpenRouterProperties;
import com.actbrow.actbrow.model.ConversationMessageEntity;
import com.actbrow.actbrow.model.ConversationMessageRole;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class OpenRouterModelProvider implements ModelProvider {

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private static final String TOOL_FORMAT_RETRY_REMINDER = """
		CRITICAL (OpenRouter): Call tools ONLY through the API tool_calls / function-calling channel.
		Do NOT write tools as plain text, XML, HTML, or tag syntax.
		Forbidden examples: <function=...>, </function>, `app.navigate(...)`, or any fake markup.
		Use exactly one native tool call when a listed function is needed; arguments must be JSON matching the schema.""";

	private final RestTemplate restTemplate;
	private final ObjectMapper objectMapper;
	private final OpenRouterProperties properties;

	public OpenRouterModelProvider(ObjectMapper objectMapper, OpenRouterProperties properties) {
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
		return "openrouter";
	}

	@Override
	public ModelDecision decideNextStep(String model, String systemPrompt, List<ConversationMessageEntity> messages,
		List<ToolDescriptor> tools, int stepIndex) {
		if (properties.apiKey() == null || properties.apiKey().isBlank()) {
			throw new IllegalArgumentException("OpenRouter API key is not configured");
		}

		String resolvedModel = model == null || model.isBlank() ? properties.defaultModel() : model;
		String url = UriComponentsBuilder.fromUriString(trimTrailingSlash(properties.baseUrl()))
			.path("/chat/completions")
			.build()
			.toUriString();

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.setBearerAuth(properties.apiKey());
		if (properties.httpReferer() != null && !properties.httpReferer().isBlank()) {
			headers.add("HTTP-Referer", properties.httpReferer());
		}
		if (properties.appTitle() != null && !properties.appTitle().isBlank()) {
			headers.add("X-Title", properties.appTitle());
		}

		String responseBody;
		try {
			responseBody = chatCompletion(url, headers, resolvedModel, systemPrompt, messages, tools, false);
		}
		catch (HttpStatusCodeException exception) {
			if (isToolUseFailed(exception) && !tools.isEmpty()) {
				try {
					responseBody = chatCompletion(url, headers, resolvedModel, systemPrompt, messages, tools, true);
				}
				catch (HttpStatusCodeException retryEx) {
					throw openRouterError(retryEx);
				}
			}
			else {
				throw openRouterError(exception);
			}
		}

		try {
			return parseDecision(responseBody, tools);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalArgumentException("Unable to parse OpenRouter response", exception);
		}
	}

	private static boolean isToolUseFailed(HttpStatusCodeException exception) {
		if (exception.getStatusCode().value() != 400) {
			return false;
		}
		String body = exception.getResponseBodyAsString();
		return body != null && body.contains("\"tool_use_failed\"");
	}

	private static IllegalArgumentException openRouterError(HttpStatusCodeException exception) {
		return new IllegalArgumentException(
			"OpenRouter request failed with status %s: %s".formatted(exception.getStatusCode(),
				exception.getResponseBodyAsString()),
			exception);
	}

	private String chatCompletion(String url, HttpHeaders headers, String model, String systemPrompt,
		List<ConversationMessageEntity> messages, List<ToolDescriptor> tools, boolean formatRetry) {
		return restTemplate.exchange(
			url,
			HttpMethod.POST,
			new HttpEntity<>(buildRequest(model, systemPrompt, messages, tools, formatRetry), headers),
			String.class).getBody();
	}

	private static String trimTrailingSlash(String base) {
		return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
	}

	private static Duration resolveTimeout(OpenRouterProperties properties) {
		return properties.requestTimeout() == null ? Duration.ofSeconds(20) : properties.requestTimeout();
	}

	private Map<String, Object> buildRequest(String model, String systemPrompt, List<ConversationMessageEntity> messages,
		List<ToolDescriptor> tools, boolean appendFormatRetryHint) {
		Map<String, Object> request = new LinkedHashMap<>();
		request.put("model", "qwen/qwen3.6-plus:free");
		request.put("messages", buildMessages(systemPrompt, messages, appendFormatRetryHint));
		request.put("temperature", 0);
		if (properties.reasoning() != null && Boolean.TRUE.equals(properties.reasoning().enabled())) {
			request.put("reasoning", Map.of("enabled", true));
		}
		if (!tools.isEmpty()) {
			request.put("tools", buildTools(tools));
			request.put("tool_choice", "auto");
			request.put("parallel_tool_calls", false);
		}
		return request;
	}

	private List<Map<String, Object>> buildMessages(String systemPrompt, List<ConversationMessageEntity> messages,
		boolean appendFormatRetryHint) {
		List<Map<String, Object>> result = new ArrayList<>();

		String systemContent = buildSystemPrompt(systemPrompt);
		if (appendFormatRetryHint) {
			systemContent = systemContent + "\n\n" + TOOL_FORMAT_RETRY_REMINDER;
		}
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
		builder.append("If a listed tool is required, the API will emit it as a native tool/function call—never as text. ");
		builder.append("If no tool is required, reply with a concise final answer in plain text only. ");
		builder.append("Use only the declared function names. Do not invent tools. ");
		builder.append("Never output XML-like tags, <function=...>, </function>, or pseudo-syntax for tools. ");
		builder.append("Never answer with plain text that looks like a tool call ");
		builder.append("(e.g. app.navigate{...} or JSON like {\"type\":\"function\",\"name\":...}); ");
		builder.append("use real function calls only. ");
		builder.append("When several navigation tools exist, prefer the specific tool whose description matches the request ");
		builder.append("(follow assistant-configured default paths in each tool description). ");
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
			throw new IllegalArgumentException("OpenRouter returned no choices");
		}

		JsonNode message = choice.path("message");
		JsonNode toolCalls = message.get("tool_calls");

		if (toolCalls != null && !toolCalls.isNull() && toolCalls.isArray() && !toolCalls.isEmpty()) {
			JsonNode firstCall = toolCalls.path(0);
			String toolKey = firstCall.path("function").path("name").asText();
			ToolDescriptor tool = tools.stream()
				.filter(item -> item.key().equals(toolKey))
				.findFirst()
				.orElseThrow(() -> new IllegalArgumentException("OpenRouter requested unknown tool: " + toolKey));

			String argumentsJson = firstCall.path("function").path("arguments").asText();
			Map<String, Object> arguments = objectMapper.readValue(argumentsJson, MAP_TYPE);

			String callId = firstCall.path("id").asText();
			return new ToolCallDecision("OpenRouter requested tool " + toolKey,
				new ToolCall(callId, tool.id(), tool.key(), arguments));
		}

		String content = message.path("content").asText(null);
		if (content == null || content.isBlank()) {
			throw new IllegalArgumentException("OpenRouter returned neither text nor function call");
		}
		ToolCallDecision recovered = PlainTextToolCallRecovery.tryRecover(content, tools, objectMapper);
		if (recovered != null) {
			return recovered;
		}
		return new FinalResponseDecision(content);
	}
}
