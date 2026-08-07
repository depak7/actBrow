package com.actbrow.actbrow.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;

import com.actbrow.actbrow.model.ConversationMessageEntity;
import com.actbrow.actbrow.model.ConversationMessageRole;
import com.fasterxml.jackson.databind.ObjectMapper;

class OpenAiCompatibleModelProviderTests {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void collidingKeysGetDistinctWireNames() {
		List<ToolDescriptor> tools = List.of(
			descriptor("t1", "a.b-c"),
			descriptor("t2", "a-b.c"),
			descriptor("t3", "a.b"),
			descriptor("t4", "a_b"));

		Map<String, ToolDescriptor> wireNames = OpenAiCompatibleModelProvider.buildWireNameMap(tools);

		assertEquals(4, wireNames.size(), "Every tool must get its own wire name");
		assertEquals("t1", wireNames.get("a_b_c").id(), "First tool in catalog order keeps the sanitized name");
		assertEquals("t2", wireNames.get("a_b_c_2").id(), "Collision gets a deterministic _2 suffix");
		assertEquals("t3", wireNames.get("a_b").id());
		assertEquals("t4", wireNames.get("a_b_2").id());
	}

	@Test
	void collidingWireNamesAreSentDistinctlyToTheModel() {
		AtomicReference<Prompt> captured = new AtomicReference<>();
		ChatModel chatModel = stubChatModel(captured, textResponse("done"));
		OpenAiCompatibleModelProvider provider = new OpenAiCompatibleModelProvider(chatModel, objectMapper);

		List<ToolDescriptor> tools = List.of(descriptor("t1", "a.b-c"), descriptor("t2", "a-b.c"));
		provider.decideNextStep("gpt-4o", "system", List.of(userMessage("hi")), tools, 0);

		OpenAiChatOptions options = (OpenAiChatOptions) captured.get().getOptions();
		List<String> names = options.getToolCallbacks().stream()
			.map(ToolCallback::getToolDefinition)
			.map(definition -> definition.name())
			.toList();
		assertEquals(List.of("a_b_c", "a_b_c_2"), names);
	}

	@Test
	void disambiguatedWireNameResolvesToTheCorrectDescriptor() {
		ChatModel chatModel = stubChatModel(new AtomicReference<>(),
			toolCallResponse("call-1", "a_b_c_2", "{\"path\":\"/x\"}"));
		OpenAiCompatibleModelProvider provider = new OpenAiCompatibleModelProvider(chatModel, objectMapper);

		List<ToolDescriptor> tools = List.of(descriptor("t1", "a.b-c"), descriptor("t2", "a-b.c"));
		ModelDecision decision = provider.decideNextStep("gpt-4o", "system", List.of(userMessage("hi")), tools, 0);

		ToolCallDecision toolDecision = assertInstanceOf(ToolCallDecision.class, decision);
		assertEquals(1, toolDecision.toolCalls().size());
		ToolCall call = toolDecision.toolCalls().get(0);
		assertEquals("t2", call.toolId(), "Suffixed wire name must resolve to the SECOND colliding tool");
		assertEquals("a-b.c", call.toolKey());
		assertEquals(Map.of("path", "/x"), call.arguments());
	}

	@Test
	void baseWireNameResolvesToTheFirstCollidingDescriptor() {
		ChatModel chatModel = stubChatModel(new AtomicReference<>(), toolCallResponse("call-1", "a_b_c", "{}"));
		OpenAiCompatibleModelProvider provider = new OpenAiCompatibleModelProvider(chatModel, objectMapper);

		List<ToolDescriptor> tools = List.of(descriptor("t1", "a.b-c"), descriptor("t2", "a-b.c"));
		ModelDecision decision = provider.decideNextStep("gpt-4o", "system", List.of(userMessage("hi")), tools, 0);

		ToolCall call = assertInstanceOf(ToolCallDecision.class, decision).toolCalls().get(0);
		assertEquals("t1", call.toolId());
		assertEquals("a.b-c", call.toolKey());
	}

	@Test
	void nonCollidingNamesKeepHistoricalSanitizedForm() {
		List<ToolDescriptor> tools = List.of(
			descriptor("t1", "crm.ticket.create"),
			descriptor("t2", "app-navigate"));

		Map<String, ToolDescriptor> wireNames = OpenAiCompatibleModelProvider.buildWireNameMap(tools);

		assertEquals("t1", wireNames.get("crm_ticket_create").id());
		assertEquals("t2", wireNames.get("app_navigate").id());

		ChatModel chatModel = stubChatModel(new AtomicReference<>(),
			toolCallResponse("call-1", "crm_ticket_create", "{}"));
		OpenAiCompatibleModelProvider provider = new OpenAiCompatibleModelProvider(chatModel, objectMapper);
		ModelDecision decision = provider.decideNextStep("gpt-4o", "system", List.of(userMessage("hi")), tools, 0);
		ToolCall call = assertInstanceOf(ToolCallDecision.class, decision).toolCalls().get(0);
		assertEquals("crm.ticket.create", call.toolKey());
	}

	@Test
	void rawKeyFallbackStillResolves() {
		ChatModel chatModel = stubChatModel(new AtomicReference<>(),
			toolCallResponse("call-1", "crm.ticket.create", "{}"));
		OpenAiCompatibleModelProvider provider = new OpenAiCompatibleModelProvider(chatModel, objectMapper);

		List<ToolDescriptor> tools = List.of(descriptor("t1", "crm.ticket.create"));
		ModelDecision decision = provider.decideNextStep("gpt-4o", "system", List.of(userMessage("hi")), tools, 0);

		ToolCall call = assertInstanceOf(ToolCallDecision.class, decision).toolCalls().get(0);
		assertEquals("t1", call.toolId());
	}

	@Test
	void inventedToolNameDoesNotThrowAndPassesThroughUnresolved() {
		ChatModel chatModel = stubChatModel(new AtomicReference<>(),
			toolCallResponse("call-1", "browser_click", "{\"selector\":\"#go\"}"));
		OpenAiCompatibleModelProvider provider = new OpenAiCompatibleModelProvider(chatModel, objectMapper);

		List<ToolDescriptor> tools = List.of(descriptor("t1", "app.navigate"), descriptor("t2", "page.observe"));
		ModelDecision decision = provider.decideNextStep("gpt-4o", "system", List.of(userMessage("hi")), tools, 0);

		ToolCall call = assertInstanceOf(ToolCallDecision.class, decision).toolCalls().get(0);
		assertEquals(null, call.toolId(), "Invented tools must not invent a descriptor id");
		assertEquals("browser_click", call.toolKey());
		assertEquals(Map.of("selector", "#go"), call.arguments());
	}

	@Test
	void sanitizedKeyEquivalenceResolvesActiveTool() {
		ChatModel chatModel = stubChatModel(new AtomicReference<>(),
			toolCallResponse("call-1", "page_observe", "{}"));
		OpenAiCompatibleModelProvider provider = new OpenAiCompatibleModelProvider(chatModel, objectMapper);

		// Offer under a wire name that is NOT page_observe (collision suffix would change it), using
		// resolveRequestedTool directly for the soft-match path when wire map misses.
		List<ToolDescriptor> tools = List.of(descriptor("t1", "page.observe"));
		Map<String, ToolDescriptor> emptyWireMap = Map.of();
		ToolDescriptor resolved = OpenAiCompatibleModelProvider.resolveRequestedTool("page_observe", tools, emptyWireMap);
		assertEquals("t1", resolved.id());
		assertEquals("page.observe", resolved.key());

		// End-to-end: wire map already has page_observe for page.observe, so native path still works.
		ModelDecision decision = provider.decideNextStep("gpt-4o", "system", List.of(userMessage("hi")), tools, 0);
		ToolCall call = assertInstanceOf(ToolCallDecision.class, decision).toolCalls().get(0);
		assertEquals("page.observe", call.toolKey());
	}

	@Test
	void longCollidingKeysAreTruncatedTo64AndStayUnique() {
		String longPrefix = "x".repeat(70);
		List<ToolDescriptor> tools = List.of(
			descriptor("t1", longPrefix + ".one"),
			descriptor("t2", longPrefix + ".two"));

		Map<String, ToolDescriptor> wireNames = OpenAiCompatibleModelProvider.buildWireNameMap(tools);

		assertEquals(2, wireNames.size());
		for (String name : wireNames.keySet()) {
			assertTrue(name.length() <= 64, "Wire name exceeds 64 chars: " + name);
			assertTrue(name.matches("^[a-zA-Z0-9_-]{1,64}$"), "Wire name violates OpenAI constraint: " + name);
		}
		assertEquals("t1", wireNames.get("x".repeat(64)).id());
		assertEquals("t2", wireNames.get("x".repeat(62) + "_2").id());
	}

	@Test
	void streamingTextEmitsDeltasInOrderAndReturnsFullAnswer() {
		List<String> deltas = new java.util.ArrayList<>();
		ChatModel chatModel = streamingChatModel(
			List.of(textResponse("Hello"), textResponse(" there"), textResponse("!")), null);
		OpenAiCompatibleModelProvider provider = new OpenAiCompatibleModelProvider(chatModel, objectMapper);

		ModelDecision decision = provider.decideNextStep("gpt-4o", "system", List.of(userMessage("hi")),
			List.of(), 0, deltas::add);

		assertEquals(List.of("Hello", " there", "!"), deltas, "Deltas stream in generation order");
		assertEquals("Hello there!", assertInstanceOf(FinalResponseDecision.class, decision).message());
	}

	@Test
	void streamingToolCallsEmitNoDeltasAndStillResolve() {
		List<String> deltas = new java.util.ArrayList<>();
		// Spring AI merges tool-call chunks upstream, so the stream yields the assembled call.
		ChatModel chatModel = streamingChatModel(
			List.of(toolCallResponse("call-1", "crm_ticket_create", "{\"id\":\"7\"}")), null);
		OpenAiCompatibleModelProvider provider = new OpenAiCompatibleModelProvider(chatModel, objectMapper);

		ModelDecision decision = provider.decideNextStep("gpt-4o", "system", List.of(userMessage("hi")),
			List.of(descriptor("t1", "crm.ticket.create")), 0, deltas::add);

		assertTrue(deltas.isEmpty(), "Tool calls must never surface as text deltas");
		ToolCall call = assertInstanceOf(ToolCallDecision.class, decision).toolCalls().get(0);
		assertEquals("t1", call.toolId());
		assertEquals(Map.of("id", "7"), call.arguments());
	}

	@Test
	void streamFailureBeforeAnyOutputFallsBackToBlockingCall() {
		List<String> deltas = new java.util.ArrayList<>();
		ChatModel chatModel = streamingChatModel(List.of(),
			new IllegalStateException("streaming not supported by backend"), textResponse("blocking answer"));
		OpenAiCompatibleModelProvider provider = new OpenAiCompatibleModelProvider(chatModel, objectMapper);

		ModelDecision decision = provider.decideNextStep("gpt-4o", "system", List.of(userMessage("hi")),
			List.of(), 0, deltas::add);

		assertTrue(deltas.isEmpty());
		assertEquals("blocking answer", assertInstanceOf(FinalResponseDecision.class, decision).message());
	}

	@Test
	void deltaConsumerFailureDoesNotBreakTheDecision() {
		ChatModel chatModel = streamingChatModel(List.of(textResponse("partial"), textResponse(" answer")), null);
		OpenAiCompatibleModelProvider provider = new OpenAiCompatibleModelProvider(chatModel, objectMapper);

		ModelDecision decision = provider.decideNextStep("gpt-4o", "system", List.of(userMessage("hi")),
			List.of(), 0, delta -> {
				throw new IllegalStateException("client went away");
			});

		assertEquals("partial answer", assertInstanceOf(FinalResponseDecision.class, decision).message());
	}

	@Test
	void blockingPathIsUsedWhenNoDeltaConsumerIsSupplied() {
		java.util.concurrent.atomic.AtomicBoolean streamed = new java.util.concurrent.atomic.AtomicBoolean();
		ChatModel chatModel = new ChatModel() {
			@Override
			public ChatResponse call(Prompt prompt) {
				return textResponse("blocking");
			}

			@Override
			public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) {
				streamed.set(true);
				return reactor.core.publisher.Flux.just(textResponse("streamed"));
			}
		};
		OpenAiCompatibleModelProvider provider = new OpenAiCompatibleModelProvider(chatModel, objectMapper);

		ModelDecision decision = provider.decideNextStep("gpt-4o", "system", List.of(userMessage("hi")), List.of(), 0);

		assertTrue(!streamed.get(), "Legacy call path must not stream");
		assertEquals("blocking", assertInstanceOf(FinalResponseDecision.class, decision).message());
	}

	private static ChatModel stubChatModel(AtomicReference<Prompt> captured, ChatResponse response) {
		return new ChatModel() {
			@Override
			public ChatResponse call(Prompt prompt) {
				captured.set(prompt);
				return response;
			}
		};
	}

	/**
	 * Stubs {@link ChatModel#stream(Prompt)} with a fixed chunk sequence, optionally terminating in an
	 * error, and answers {@link ChatModel#call(Prompt)} with {@code blockingResponses} for fallback.
	 */
	private static ChatModel streamingChatModel(List<ChatResponse> chunks, RuntimeException failure,
		ChatResponse... blockingResponses) {
		return new ChatModel() {
			@Override
			public ChatResponse call(Prompt prompt) {
				if (blockingResponses.length == 0) {
					throw new IllegalStateException("blocking call not expected in this test");
				}
				return blockingResponses[0];
			}

			@Override
			public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) {
				reactor.core.publisher.Flux<ChatResponse> flux = reactor.core.publisher.Flux.fromIterable(chunks);
				return failure == null ? flux : flux.concatWith(reactor.core.publisher.Flux.error(failure));
			}
		};
	}

	private static ChatResponse toolCallResponse(String callId, String wireName, String argumentsJson) {
		AssistantMessage assistant = AssistantMessage.builder()
			.content("")
			.toolCalls(List.of(new AssistantMessage.ToolCall(callId, "function", wireName, argumentsJson)))
			.build();
		return new ChatResponse(List.of(new Generation(assistant)));
	}

	private static ChatResponse textResponse(String text) {
		return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
	}

	private static ToolDescriptor descriptor(String id, String key) {
		return new ToolDescriptor(id, key, "desc for " + key, "{\"type\":\"object\"}", null, null, Map.of(), Map.of());
	}

	private static ConversationMessageEntity userMessage(String content) {
		ConversationMessageEntity entity = new ConversationMessageEntity();
		entity.setRole(ConversationMessageRole.USER);
		entity.setContent(content);
		return entity;
	}
}
