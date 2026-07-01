package com.actbrow.actbrow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

@SuppressWarnings("unchecked")
class ClaudeCliServiceTests {

	private ObjectMapper objectMapper;

	/** Captures the last command args and returns a caller-supplied response. */
	private static class StubCliService extends ClaudeCliService {

		String lastConversationPrompt;
		String lastSystemPrompt;
		String lastModel;
		String stubbedOutput;

		StubCliService(ObjectMapper objectMapper, String stubbedOutput) {
			super(objectMapper);
			this.stubbedOutput = stubbedOutput;
		}

		@Override
		protected String runClaude(String conversationPrompt, String systemPrompt, String model) {
			this.lastConversationPrompt = conversationPrompt;
			this.lastSystemPrompt = systemPrompt;
			this.lastModel = model;
			return stubbedOutput;
		}
	}

	@BeforeEach
	void setUp() {
		objectMapper = new ObjectMapper();
	}

	// ─── Response structure ────────────────────────────────────────────────────

	@Test
	void plainTextOutputReturnsStopFinishReason() {
		var service = new StubCliService(objectMapper, "Hello there!");
		Map<String, Object> result = service.complete(Map.of(
			"model", "claude-sonnet-4-6",
			"messages", List.of(Map.of("role", "user", "content", "hi"))));

		assertThat(result).containsKey("id");
		assertThat(result.get("object")).isEqualTo("chat.completion");
		assertThat(result.get("model")).isEqualTo("claude-sonnet-4-6");

		List<Map<String, Object>> choices = (List<Map<String, Object>>) result.get("choices");
		assertThat(choices).hasSize(1);

		Map<String, Object> choice = choices.get(0);
		assertThat(choice.get("finish_reason")).isEqualTo("stop");

		Map<String, Object> message = (Map<String, Object>) choice.get("message");
		assertThat(message.get("role")).isEqualTo("assistant");
		assertThat(message.get("content")).isEqualTo("Hello there!");
	}

	@Test
	void responseContainsUsageBlock() {
		var service = new StubCliService(objectMapper, "ok");
		Map<String, Object> result = service.complete(Map.of(
			"messages", List.of(Map.of("role", "user", "content", "ping"))));

		Map<String, Object> usage = (Map<String, Object>) result.get("usage");
		assertThat(usage).containsKeys("prompt_tokens", "completion_tokens", "total_tokens");
	}

	@Test
	void responseIdHasChatcmplLocalPrefix() {
		var service = new StubCliService(objectMapper, "hi");
		Map<String, Object> result = service.complete(Map.of(
			"messages", List.of(Map.of("role", "user", "content", "hello"))));

		assertThat((String) result.get("id")).startsWith("chatcmpl-local-");
	}

	// ─── Tool call parsing ─────────────────────────────────────────────────────

	@Test
	void singleToolCallBlockParsedCorrectly() {
		String output = "<tool_call>\n{\"name\": \"navigate\", \"id\": \"call_1\", \"arguments\": {\"url\": \"/orders\"}}\n</tool_call>";
		var service = new StubCliService(objectMapper, output);

		Map<String, Object> result = service.complete(Map.of(
			"messages", List.of(Map.of("role", "user", "content", "go to orders"))));

		List<Map<String, Object>> choices = (List<Map<String, Object>>) result.get("choices");
		Map<String, Object> choice = choices.get(0);
		assertThat(choice.get("finish_reason")).isEqualTo("tool_calls");

		Map<String, Object> message = (Map<String, Object>) choice.get("message");
		assertThat(message.get("content")).isNull();

		List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) message.get("tool_calls");
		assertThat(toolCalls).hasSize(1);

		Map<String, Object> call = toolCalls.get(0);
		assertThat(call.get("id")).isEqualTo("call_1");
		assertThat(call.get("type")).isEqualTo("function");

		Map<String, Object> fn = (Map<String, Object>) call.get("function");
		assertThat(fn.get("name")).isEqualTo("navigate");
		assertThat(fn.get("arguments").toString()).contains("orders");
	}

	@Test
	void multipleToolCallBlocksAllExtracted() {
		String output = """
			<tool_call>
			{"name": "click", "id": "call_a", "arguments": {"selector": "#btn"}}
			</tool_call>
			<tool_call>
			{"name": "scroll", "id": "call_b", "arguments": {"direction": "down"}}
			</tool_call>
			""";
		var service = new StubCliService(objectMapper, output);

		Map<String, Object> result = service.complete(Map.of(
			"messages", List.of(Map.of("role", "user", "content", "do two things"))));

		List<Map<String, Object>> choices = (List<Map<String, Object>>) result.get("choices");
		Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
		List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) message.get("tool_calls");

		assertThat(toolCalls).hasSize(2);
		assertThat(toolCalls.get(0).get("id")).isEqualTo("call_a");
		assertThat(toolCalls.get(1).get("id")).isEqualTo("call_b");
	}

	@Test
	void toolCallWithNoIdGetsGeneratedId() {
		String output = "<tool_call>\n{\"name\": \"ping\", \"arguments\": {}}\n</tool_call>";
		var service = new StubCliService(objectMapper, output);

		Map<String, Object> result = service.complete(Map.of(
			"messages", List.of(Map.of("role", "user", "content", "ping"))));

		List<Map<String, Object>> choices = (List<Map<String, Object>>) result.get("choices");
		Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
		List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) message.get("tool_calls");

		assertThat((String) toolCalls.get(0).get("id")).isNotBlank();
	}

	@Test
	void malformedToolCallBlockSkipped() {
		String output = "<tool_call>not json</tool_call>\nActual response text";
		var service = new StubCliService(objectMapper, output);

		Map<String, Object> result = service.complete(Map.of(
			"messages", List.of(Map.of("role", "user", "content", "hi"))));

		List<Map<String, Object>> choices = (List<Map<String, Object>>) result.get("choices");
		Map<String, Object> choice = choices.get(0);
		// malformed block is skipped → treated as text response
		assertThat(choice.get("finish_reason")).isEqualTo("stop");
	}

	// ─── Conversation prompt formatting ────────────────────────────────────────

	@Test
	void userMessageFormattedWithHumanPrefix() {
		var service = new StubCliService(objectMapper, "ok");
		service.complete(Map.of(
			"messages", List.of(Map.of("role", "user", "content", "what is 2+2?"))));

		assertThat(service.lastConversationPrompt).contains("Human: what is 2+2?");
	}

	@Test
	void assistantTextMessageFormattedWithAssistantPrefix() {
		var service = new StubCliService(objectMapper, "ok");
		service.complete(Map.of("messages", List.of(
			Map.of("role", "user", "content", "first"),
			Map.of("role", "assistant", "content", "I said something"),
			Map.of("role", "user", "content", "second"))));

		assertThat(service.lastConversationPrompt).contains("Assistant: I said something");
	}

	@Test
	void assistantToolCallsMessageFormattedWithCalledToolsLine() {
		Map<String, Object> toolCall = Map.of(
			"id", "call_1",
			"type", "function",
			"function", Map.of("name", "navigate", "arguments", "{}"));

		// Map.of() disallows null values — use LinkedHashMap for the assistant message
		Map<String, Object> assistantMsg = new java.util.LinkedHashMap<>();
		assistantMsg.put("role", "assistant");
		assistantMsg.put("content", null);
		assistantMsg.put("tool_calls", List.of(toolCall));

		var service = new StubCliService(objectMapper, "ok");
		service.complete(Map.of("messages", List.of(
			Map.of("role", "user", "content", "go"),
			assistantMsg)));

		assertThat(service.lastConversationPrompt).contains("[called tools: navigate]");
	}

	@Test
	void toolResultMessageFormattedWithToolCallId() {
		var service = new StubCliService(objectMapper, "ok");
		service.complete(Map.of("messages", List.of(
			Map.of("role", "user", "content", "go"),
			Map.of("role", "tool", "content", "Navigated to /orders", "tool_call_id", "call_xyz"))));

		assertThat(service.lastConversationPrompt).contains("Tool result (call_xyz): Navigated to /orders");
	}

	@Test
	void systemMessageExcludedFromConversationPrompt() {
		var service = new StubCliService(objectMapper, "ok");
		service.complete(Map.of("messages", List.of(
			Map.of("role", "system", "content", "You are helpful."),
			Map.of("role", "user", "content", "hi"))));

		assertThat(service.lastConversationPrompt).doesNotContain("You are helpful.");
		assertThat(service.lastConversationPrompt).contains("Human: hi");
	}

	@Test
	void conversationPromptEndsWithAssistantContinuationMarker() {
		var service = new StubCliService(objectMapper, "ok");
		service.complete(Map.of(
			"messages", List.of(Map.of("role", "user", "content", "hello"))));

		assertThat(service.lastConversationPrompt.trim()).endsWith("Assistant:");
	}

	// ─── System prompt building ─────────────────────────────────────────────────

	@Test
	void systemMessageContentPassedAsSystemPrompt() {
		var service = new StubCliService(objectMapper, "ok");
		service.complete(Map.of("messages", List.of(
			Map.of("role", "system", "content", "Always be concise."),
			Map.of("role", "user", "content", "hi"))));

		assertThat(service.lastSystemPrompt).contains("Always be concise.");
	}

	@Test
	void toolDefinitionsInjectedIntoSystemPrompt() {
		List<Map<String, Object>> tools = List.of(Map.of(
			"type", "function",
			"function", Map.of("name", "search", "description", "Search the KB")));
		var service = new StubCliService(objectMapper, "ok");
		service.complete(Map.of(
			"messages", List.of(Map.of("role", "user", "content", "find me stuff")),
			"tools", tools));

		assertThat(service.lastSystemPrompt).contains("<tool_call>");
		assertThat(service.lastSystemPrompt).contains("search");
		assertThat(service.lastSystemPrompt).contains("Available tools:");
	}

	@Test
	void noToolInjectionWhenToolsListEmpty() {
		var service = new StubCliService(objectMapper, "ok");
		service.complete(Map.of(
			"messages", List.of(Map.of("role", "user", "content", "hi")),
			"tools", List.of()));

		assertThat(service.lastSystemPrompt).doesNotContain("<tool_call>");
	}

	// ─── Model name resolution ─────────────────────────────────────────────────

	@Test
	void geminiPrefixStrippedFromModel() {
		var service = new StubCliService(objectMapper, "ok");
		service.complete(Map.of(
			"model", "gemini:gemini-2.5-flash",
			"messages", List.of(Map.of("role", "user", "content", "hi"))));

		assertThat(service.lastModel).isEqualTo("gemini-2.5-flash");
	}

	@Test
	void openaiPrefixStrippedFromModel() {
		var service = new StubCliService(objectMapper, "ok");
		service.complete(Map.of(
			"model", "openai:gpt-4o",
			"messages", List.of(Map.of("role", "user", "content", "hi"))));

		assertThat(service.lastModel).isEqualTo("gpt-4o");
	}

	@Test
	void openrouterSlashModelPassedThrough() {
		var service = new StubCliService(objectMapper, "ok");
		service.complete(Map.of(
			"model", "anthropic/claude-3.5-sonnet",
			"messages", List.of(Map.of("role", "user", "content", "hi"))));

		assertThat(service.lastModel).isEqualTo("anthropic/claude-3.5-sonnet");
	}

	@Test
	void modelDefaultsToClaudeSonnetWhenAbsent() {
		var service = new StubCliService(objectMapper, "ok");
		service.complete(Map.of(
			"messages", List.of(Map.of("role", "user", "content", "hi"))));

		assertThat(service.lastModel).isEqualTo("claude-sonnet-4-6");
	}

	// ─── Edge cases ─────────────────────────────────────────────────────────────

	@Test
	void emptyMessagesListProducesValidResponse() {
		var service = new StubCliService(objectMapper, "I have nothing to say.");
		Map<String, Object> result = service.complete(Map.of("messages", List.of()));

		List<Map<String, Object>> choices = (List<Map<String, Object>>) result.get("choices");
		assertThat(choices).hasSize(1);
		Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
		assertThat(message.get("content")).isEqualTo("I have nothing to say.");
	}

	@Test
	void multiTurnConversationAllMessagesIncluded() {
		var service = new StubCliService(objectMapper, "sure");
		service.complete(Map.of("messages", List.of(
			Map.of("role", "user", "content", "turn one"),
			Map.of("role", "assistant", "content", "response one"),
			Map.of("role", "user", "content", "turn two"))));

		String prompt = service.lastConversationPrompt;
		assertThat(prompt).contains("Human: turn one");
		assertThat(prompt).contains("Assistant: response one");
		assertThat(prompt).contains("Human: turn two");
	}

	// ─── Integration tests (require `claude` CLI on PATH and logged in) ─────────

	@Test
	@Tag("integration")
	void realCliReturnsNonEmptyTextResponse() {
		assumeClaudeAvailable();
		var service = new ClaudeCliService(objectMapper);
		Map<String, Object> result = service.complete(Map.of(
			"model", "claude-haiku-4-5",
			"messages", List.of(Map.of("role", "user", "content", "Reply with exactly the word PONG and nothing else."))));

		List<Map<String, Object>> choices = (List<Map<String, Object>>) result.get("choices");
		Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
		String content = (String) message.get("content");

		assertThat(content).isNotBlank();
		assertThat(choices.get(0).get("finish_reason")).isEqualTo("stop");
	}

	@Test
	@Tag("integration")
	void realCliReturnsValidOpenAiShape() {
		assumeClaudeAvailable();
		var service = new ClaudeCliService(objectMapper);
		Map<String, Object> result = service.complete(Map.of(
			"model", "claude-haiku-4-5",
			"messages", List.of(Map.of("role", "user", "content", "Say hi."))));

		assertThat((String) result.get("id")).startsWith("chatcmpl-local-");
		assertThat(result.get("object")).isEqualTo("chat.completion");
		assertThat(result.get("created")).isNotNull();
		assertThat(result.get("choices")).isNotNull();
		assertThat(result.get("usage")).isNotNull();
	}

	private static void assumeClaudeAvailable() {
		boolean available = false;
		try {
			Process p = new ProcessBuilder("claude", "--version")
				.redirectErrorStream(true)
				.start();
			p.waitFor();
			available = p.exitValue() == 0;
		}
		catch (Exception ignored) {}
		assumeThat(available).as("claude CLI not available — skipping integration test").isTrue();
	}

}
