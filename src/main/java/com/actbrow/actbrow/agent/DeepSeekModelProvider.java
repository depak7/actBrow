package com.actbrow.actbrow.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
 * Sole {@link ModelProvider} since Phase 1 collapsed actbrow to a single model. Delegates to
 * Spring AI's OpenAI-compatible {@link ChatModel} bean, which is configured against DeepSeek's
 * chat-completions endpoint via {@code spring.ai.openai.*} properties.
 *
 * Spring AI's internal tool execution is disabled — actbrow's {@code RunService} owns the
 * tool-call dispatch loop, so this provider only needs to surface tool-call requests back as
 * {@link ToolCallDecision} for the existing run pipeline to handle.
 */
@Component
public class DeepSeekModelProvider implements ModelProvider {

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private final ChatModel chatModel;
	private final ObjectMapper objectMapper;

	public DeepSeekModelProvider(ChatModel chatModel, ObjectMapper objectMapper) {
		this.chatModel = chatModel;
		this.objectMapper = objectMapper;
	}

	@Override
	public String providerKey() {
		return "deepseek";
	}

	@Override
	public ModelDecision decideNextStep(String model, String systemPrompt, List<ConversationMessageEntity> messages,
		List<ToolDescriptor> tools, int stepIndex) {

		List<Message> springMessages = buildMessages(systemPrompt, messages);

		OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
			.internalToolExecutionEnabled(false);

		String resolvedModel = stripProviderPrefix(model);
		if (resolvedModel != null && !resolvedModel.isBlank()) {
			optionsBuilder.model(resolvedModel);
		}

		if (!tools.isEmpty()) {
			optionsBuilder.toolCallbacks(buildToolCallbacks(tools));
		}

		Prompt prompt = new Prompt(springMessages, optionsBuilder.build());
		ChatResponse response = chatModel.call(prompt);
		return parseDecision(response, tools);
	}

	/**
	 * Legacy assistants may have model values like {@code "gemini:gemini-pro"} from the deleted
	 * RoutingModelProvider. Strip the provider prefix so we pass a clean model name to DeepSeek;
	 * if DeepSeek doesn't recognize the trailing name it'll surface a clear error from the API.
	 */
	private static String stripProviderPrefix(String model) {
		if (model == null) {
			return null;
		}
		int idx = model.indexOf(':');
		return idx >= 0 ? model.substring(idx + 1).trim() : model;
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

	/**
	 * Parses the {@code [tool_calls]<json-array>[/tool_calls]} envelope written by
	 * {@code RunService#buildAssistantToolCallsJson}. Phase 2b deletes this envelope; until then
	 * we round-trip through it.
	 */
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

	private List<ToolCallback> buildToolCallbacks(List<ToolDescriptor> tools) {
		List<ToolCallback> callbacks = new ArrayList<>();
		for (ToolDescriptor tool : tools) {
			ToolDefinition definition = ToolDefinition.builder()
				.name(sanitizeToolName(tool.key()))
				.description(ModelToolPresentation.descriptionForModel(tool, objectMapper))
				.inputSchema(tool.inputSchema())
				.build();
			callbacks.add(new ProxiedToolCallback(definition));
		}
		return callbacks;
	}

	/**
	 * A tool callback that publishes only its definition. Spring AI is configured with
	 * {@code internalToolExecutionEnabled=false} so {@link #call(String)} should never run;
	 * if it does, throwing here surfaces the misconfiguration loudly.
	 */
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
				"DeepSeekModelProvider proxies tool execution to RunService. Spring AI should not call this directly.");
		}
	}

	private static String sanitizeToolName(String name) {
		return name == null ? "" : name.replace(".", "_").replace("-", "_");
	}

	private ModelDecision parseDecision(ChatResponse response, List<ToolDescriptor> tools) {
		if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
			throw new IllegalArgumentException("DeepSeek returned an empty response");
		}
		AssistantMessage assistant = response.getResult().getOutput();
		List<AssistantMessage.ToolCall> springToolCalls = assistant.getToolCalls();

		if (springToolCalls != null && !springToolCalls.isEmpty()) {
			List<ToolCall> calls = new ArrayList<>();
			for (AssistantMessage.ToolCall stc : springToolCalls) {
				String requestedName = stc.name();
				ToolDescriptor tool = tools.stream()
					.filter(t -> sanitizeToolName(t.key()).equals(requestedName) || t.key().equals(requestedName))
					.findFirst()
					.orElseThrow(() -> new IllegalArgumentException(
						"DeepSeek requested unknown tool: " + requestedName));
				Map<String, Object> arguments = parseArguments(stc.arguments());
				String callId = (stc.id() != null && !stc.id().isBlank())
					? stc.id()
					: "ds-" + UUID.randomUUID();
				calls.add(new ToolCall(callId, tool.id(), tool.key(), arguments));
			}
			return new ToolCallDecision("DeepSeek requested " + calls.size() + " tool(s)", calls);
		}

		String text = assistant.getText();
		if (text == null || text.isBlank()) {
			throw new IllegalArgumentException("DeepSeek returned neither text nor tool calls");
		}
		return new FinalResponseDecision(text);
	}

	private Map<String, Object> parseArguments(String argumentsJson) {
		if (argumentsJson == null || argumentsJson.isBlank()) {
			return new LinkedHashMap<>();
		}
		try {
			return objectMapper.readValue(argumentsJson, MAP_TYPE);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalArgumentException("DeepSeek returned non-JSON tool arguments: " + argumentsJson, exception);
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
		builder.append("Do not use the OPTIONS format unless you are actually asking the user to choose.");
		return builder.toString();
	}
}
