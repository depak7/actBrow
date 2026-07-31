package com.actbrow.actbrow.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import com.actbrow.actbrow.model.ConversationMessageEntity;
import com.actbrow.actbrow.model.ConversationMessageRole;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@link ModelProvider} backed by Spring AI's OpenAI-compatible {@link ChatModel} bean.
 * Configure the upstream API via {@code spring.ai.openai.base-url}, {@code spring.ai.openai.api-key},
 * and {@code spring.ai.openai.chat.options.model} (Gemini, OpenAI, OpenRouter, vLLM, etc.).
 *
 * Spring AI's internal tool execution is disabled — actbrow's {@code RunService} owns the
 * tool-call dispatch loop, so this provider only surfaces tool-call requests as {@link ToolCallDecision}.
 */
@Component
public class OpenAiCompatibleModelProvider implements ModelProvider {

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	/** OpenAI function names must match {@code ^[a-zA-Z0-9_-]{1,64}$}. */
	private static final int MAX_WIRE_NAME_LENGTH = 64;

	private final ChatModel chatModel;
	private final ObjectMapper objectMapper;

	public OpenAiCompatibleModelProvider(ChatModel chatModel, ObjectMapper objectMapper) {
		this.chatModel = chatModel;
		this.objectMapper = objectMapper;
	}

	@Override
	public ModelDecision decideNextStep(String model, String systemPrompt, List<ConversationMessageEntity> messages,
		List<ToolDescriptor> tools, int stepIndex) {
		return decideNextStep(model, systemPrompt, messages, tools, stepIndex, null);
	}

	@Override
	public ModelDecision decideNextStep(String model, String systemPrompt, List<ConversationMessageEntity> messages,
		List<ToolDescriptor> tools, int stepIndex, Consumer<String> onTextDelta) {

		List<Message> springMessages = buildMessages(systemPrompt, messages);

		OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
			.internalToolExecutionEnabled(false);

		String resolvedModel = stripLegacyProviderPrefix(model);
		if (resolvedModel != null && !resolvedModel.isBlank()) {
			optionsBuilder.model(resolvedModel);
		}

		Map<String, ToolDescriptor> toolsByWireName = buildWireNameMap(tools);
		if (!tools.isEmpty()) {
			optionsBuilder.toolCallbacks(buildToolCallbacks(toolsByWireName));
		}

		Prompt prompt = new Prompt(springMessages, optionsBuilder.build());
		if (onTextDelta != null) {
			try {
				return streamDecision(prompt, tools, toolsByWireName, onTextDelta);
			}
			catch (StreamingUnavailableException exception) {
				// Backend rejected the streaming request before producing anything — the blocking
				// call is equivalent, just without incremental deltas.
			}
		}
		ChatResponse response = chatModel.call(prompt);
		return parseDecision(response, tools, toolsByWireName);
	}

	/**
	 * Streams the completion, pushing TEXT chunks to {@code onTextDelta} as they arrive. Tool calls
	 * are never surfaced as deltas: Spring AI's OpenAI stream merges tool-call chunks into complete
	 * calls ({@code OpenAiStreamFunctionCallingHelper}), which are collected here and returned as a
	 * normal {@link ToolCallDecision}. A failure before any output falls back to the blocking call.
	 */
	private ModelDecision streamDecision(Prompt prompt, List<ToolDescriptor> tools,
		Map<String, ToolDescriptor> toolsByWireName, Consumer<String> onTextDelta) {
		StringBuilder text = new StringBuilder();
		Map<String, AssistantMessage.ToolCall> toolCallsById = new LinkedHashMap<>();
		boolean anyOutput = false;
		try {
			for (ChatResponse chunk : chatModel.stream(prompt).toIterable()) {
				if (chunk == null || chunk.getResult() == null || chunk.getResult().getOutput() == null) {
					continue;
				}
				AssistantMessage output = chunk.getResult().getOutput();
				if (output.getToolCalls() != null) {
					for (AssistantMessage.ToolCall toolCall : output.getToolCalls()) {
						String id = toolCall.id() == null || toolCall.id().isBlank()
							? "stream-idx-" + toolCallsById.size()
							: toolCall.id();
						toolCallsById.put(id, toolCall);
						anyOutput = true;
					}
				}
				String delta = output.getText();
				if (delta != null && !delta.isEmpty()) {
					text.append(delta);
					anyOutput = true;
					try {
						onTextDelta.accept(delta);
					}
					catch (Exception ignored) {
						// Delta delivery is best-effort UI sugar; never let it break the decision.
					}
				}
			}
		}
		catch (RuntimeException exception) {
			if (!anyOutput) {
				throw new StreamingUnavailableException(exception);
			}
			// Mid-stream failure after partial output: surface it — a silently truncated answer
			// must not be treated as a complete decision.
			throw exception;
		}
		if (!toolCallsById.isEmpty()) {
			return toToolCallDecision(new ArrayList<>(toolCallsById.values()), tools, toolsByWireName);
		}
		if (text.length() == 0 || text.toString().isBlank()) {
			throw new IllegalArgumentException("Chat model returned neither text nor tool calls");
		}
		return new FinalResponseDecision(text.toString());
	}

	/** The streaming request failed before producing output; the blocking call is a safe retry. */
	private static final class StreamingUnavailableException extends RuntimeException {
		StreamingUnavailableException(Throwable cause) {
			super(cause);
		}
	}

	/**
	 * Legacy assistants may store {@code "gemini:gemini-pro"} from the removed multi-provider router.
	 * Strip that prefix only; modern ids (e.g. OpenRouter {@code anthropic/claude-3.5-sonnet}) pass through unchanged.
	 */
	private static String stripLegacyProviderPrefix(String model) {
		if (model == null) {
			return null;
		}
		int idx = model.indexOf(':');
		if (idx < 0) {
			return model;
		}
		String prefix = model.substring(0, idx).trim().toLowerCase();
		if ("gemini".equals(prefix) || "openai".equals(prefix)) {
			return model.substring(idx + 1).trim();
		}
		return model;
	}

	private List<Message> buildMessages(String systemPrompt, List<ConversationMessageEntity> messages) {
		List<Message> result = new ArrayList<>();
		result.add(new SystemMessage(buildFullSystemPrompt(systemPrompt)));

		for (ConversationMessageEntity message : ModelConversationWindow.forModel(messages)) {
			String content = message.getContent();
			ConversationMessageRole role = message.getRole();

			if (role == ConversationMessageRole.ASSISTANT && isToolCallsEnvelope(content)) {
				List<AssistantMessage.ToolCall> toolCalls = parseToolCallsEnvelope(content);
				if (!toolCalls.isEmpty()) {
					result.add(AssistantMessage.builder()
						.content("")
						.toolCalls(toolCalls)
						.build());
				}
				continue;
			}

			if (role == ConversationMessageRole.TOOL) {
				String toolCallId = message.getToolCallId() != null ? message.getToolCallId() : message.getId();
				result.add(ToolResponseMessage.builder()
					.responses(List.of(new ToolResponseMessage.ToolResponse(toolCallId, "", "Tool result: " + content)))
					.build());
				continue;
			}

			if (role == ConversationMessageRole.USER) {
				result.add(new UserMessage(content == null ? "" : content));
				continue;
			}

			if (role == ConversationMessageRole.ASSISTANT) {
				result.add(new AssistantMessage(content == null ? "" : content));
			}
		}
		return result;
	}

	private static boolean isToolCallsEnvelope(String content) {
		return content != null && content.startsWith("[tool_calls]");
	}

	private List<AssistantMessage.ToolCall> parseToolCallsEnvelope(String content) {
		try {
			String jsonArray = content
				.replace("[tool_calls]", "")
				.replace("[/tool_calls]", "")
				.trim();
			JsonNode arr = objectMapper.readTree(jsonArray);
			if (!arr.isArray()) {
				return List.of();
			}
			List<AssistantMessage.ToolCall> calls = new ArrayList<>();
			for (JsonNode node : arr) {
				String id = node.path("id").asText();
				String type = node.path("type").asText("function");
				String name = sanitizeToolName(node.path("function").path("name").asText());
				String args = node.path("function").path("arguments").asText("{}");
				calls.add(new AssistantMessage.ToolCall(id, type, name, args));
			}
			return calls;
		}
		catch (JsonProcessingException exception) {
			return List.of();
		}
	}

	private List<ToolCallback> buildToolCallbacks(Map<String, ToolDescriptor> toolsByWireName) {
		List<ToolCallback> callbacks = new ArrayList<>();
		for (Map.Entry<String, ToolDescriptor> entry : toolsByWireName.entrySet()) {
			ToolDescriptor tool = entry.getValue();
			ToolDefinition definition = ToolDefinition.builder()
				.name(entry.getKey())
				.description(ModelToolPresentation.descriptionForModel(tool, objectMapper))
				.inputSchema(tool.inputSchema())
				.build();
			callbacks.add(new ProxiedToolCallback(definition));
		}
		return callbacks;
	}

	/**
	 * Maps each tool to a unique OpenAI-safe wire name ({@code ^[a-zA-Z0-9_-]{1,64}$}) for this request.
	 * The first tool (catalog order) whose sanitized key produces a given name keeps it — matching the
	 * historical {@link #sanitizeToolName(String)} behavior so stored conversations stay compatible —
	 * while later collisions get a deterministic {@code _2}, {@code _3}, ... suffix. Names longer than
	 * 64 characters are truncated before the suffix is applied so uniqueness is preserved.
	 */
	static Map<String, ToolDescriptor> buildWireNameMap(List<ToolDescriptor> tools) {
		Map<String, ToolDescriptor> byWireName = new LinkedHashMap<>();
		for (ToolDescriptor tool : tools) {
			String base = sanitizeToolName(tool.key()).replaceAll("[^a-zA-Z0-9_-]", "_");
			if (base.isEmpty()) {
				base = "tool";
			}
			String candidate = truncate(base, MAX_WIRE_NAME_LENGTH);
			int suffix = 2;
			while (byWireName.containsKey(candidate)) {
				String suffixText = "_" + suffix++;
				candidate = truncate(base, MAX_WIRE_NAME_LENGTH - suffixText.length()) + suffixText;
			}
			byWireName.put(candidate, tool);
		}
		return byWireName;
	}

	private static String truncate(String value, int maxLength) {
		return value.length() <= maxLength ? value : value.substring(0, maxLength);
	}

	private static final class ProxiedToolCallback implements ToolCallback {
		private final ToolDefinition definition;

		ProxiedToolCallback(ToolDefinition definition) {
			this.definition = definition;
		}

		@Override
		public ToolDefinition getToolDefinition() {
			return definition;
		}

		@Override
		public String call(String toolInput) {
			throw new IllegalStateException(
				"OpenAiCompatibleModelProvider proxies tool execution to RunService. Spring AI should not call this directly.");
		}
	}

	private static String sanitizeToolName(String name) {
		return name == null ? "" : name.replace(".", "_").replace("-", "_");
	}

	private ModelDecision parseDecision(ChatResponse response, List<ToolDescriptor> tools,
		Map<String, ToolDescriptor> toolsByWireName) {
		if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
			throw new IllegalArgumentException("Chat model returned an empty response");
		}
		AssistantMessage assistant = response.getResult().getOutput();
		List<AssistantMessage.ToolCall> springToolCalls = assistant.getToolCalls();

		if (springToolCalls != null && !springToolCalls.isEmpty()) {
			return toToolCallDecision(springToolCalls, tools, toolsByWireName);
		}

		String text = assistant.getText();
		if (text == null || text.isBlank()) {
			throw new IllegalArgumentException("Chat model returned neither text nor tool calls");
		}
		return new FinalResponseDecision(text);
	}

	private ToolCallDecision toToolCallDecision(List<AssistantMessage.ToolCall> springToolCalls,
		List<ToolDescriptor> tools, Map<String, ToolDescriptor> toolsByWireName) {
		List<ToolCall> calls = new ArrayList<>();
		for (AssistantMessage.ToolCall stc : springToolCalls) {
			String requestedName = stc.name();
			ToolDescriptor tool = toolsByWireName.get(requestedName);
			if (tool == null) {
				// Fallback for models echoing the raw catalog key instead of the wire name.
				tool = tools.stream()
					.filter(t -> t.key().equals(requestedName))
					.findFirst()
					.orElseThrow(() -> new IllegalArgumentException(
						"Chat model requested unknown tool: " + requestedName));
			}
			Map<String, Object> arguments = parseArguments(stc.arguments());
			String callId = (stc.id() != null && !stc.id().isBlank())
				? stc.id()
				: "tc-" + UUID.randomUUID();
			calls.add(new ToolCall(callId, tool.id(), tool.key(), arguments));
		}
		return new ToolCallDecision("Model requested " + calls.size() + " tool(s)", calls);
	}

	private Map<String, Object> parseArguments(String argumentsJson) {
		if (argumentsJson == null || argumentsJson.isBlank()) {
			return new LinkedHashMap<>();
		}
		try {
			return objectMapper.readValue(argumentsJson, MAP_TYPE);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalArgumentException("Chat model returned non-JSON tool arguments: " + argumentsJson, exception);
		}
	}

	private String buildFullSystemPrompt(String systemPrompt) {
		StringBuilder builder = new StringBuilder();
		if (systemPrompt != null && !systemPrompt.isBlank()) {
			builder.append(systemPrompt).append("\n\n");
		}
		builder.append("You are the backend decision engine for an embedded SaaS assistant. ");
		builder.append("If a listed tool is required, the API will emit it as a native tool/function call—never as text. ");
		builder.append("If no tool is required, reply with a concise final answer in plain text only. ");
		builder.append("Use only the declared function names. Do not invent tools. ");
		builder.append("When several navigation tools exist, prefer the specific tool whose description matches the request ");
		builder.append("(follow assistant-configured default paths in each tool description). ");
		builder.append("After a tool result appears in the conversation, use it to continue toward a final answer. ");
		builder.append("Always prioritize the latest user turn over older tool failures or older requests. ");
		builder.append("Do not mention a previous tool failure unless the latest user turn is clearly continuing that same task. ");
		builder.append("If the latest user turn is short, ambiguous, or could refer to multiple destinations or actions, ");
		builder.append("ask a clarifying question instead of claiming failure. ");
		builder.append("When you ask a clarifying question, offer 2 to 4 concrete options and format them exactly like this: ");
		builder.append("first the question in plain text, then a new line `OPTIONS: option one | option two`, ");
		builder.append("and optionally a new line `RECOMMENDED: option one`. ");
		builder.append("Use the same OPTIONS format when pausing a guided walkthrough so the user can click Continue to proceed. ");
		builder.append("Do not use the OPTIONS format unless you are asking the user to choose or continue a tour.");
		return builder.toString();
	}

}
