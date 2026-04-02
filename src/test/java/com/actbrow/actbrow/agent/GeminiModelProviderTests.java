package com.actbrow.actbrow.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import com.actbrow.actbrow.config.GeminiProperties;
import com.actbrow.actbrow.model.ConversationMessageEntity;
import com.actbrow.actbrow.model.ConversationMessageRole;
import com.fasterxml.jackson.databind.ObjectMapper;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

class GeminiModelProviderTests {

	private static final MockWebServer GEMINI_SERVER = startServer();

	@AfterAll
	static void shutdown() throws IOException {
		GEMINI_SERVER.shutdown();
	}

	@Test
	void mapsTextResponseToFinalDecision() {
		GEMINI_SERVER.enqueue(new MockResponse()
			.setHeader("Content-Type", "application/json")
			.setBody("""
				{
				  "candidates": [{
				    "content": {
				      "parts": [{"text": "Final answer"}]
				    }
				  }]
				}
				"""));

		GeminiModelProvider provider = provider();
		ModelDecision decision = provider.decideNextStep("gemini-2.0-flash", "Be helpful",
			List.of(message(ConversationMessageRole.USER, "hello")), List.of(), 0);

		FinalResponseDecision finalResponse = assertInstanceOf(FinalResponseDecision.class, decision);
		assertEquals("Final answer", finalResponse.message());
	}

	@Test
	void mapsFunctionCallToToolDecision() {
		GEMINI_SERVER.enqueue(new MockResponse()
			.setHeader("Content-Type", "application/json")
			.setBody("""
				{
				  "candidates": [{
				    "content": {
				      "parts": [{
				        "functionCall": {
				          "name": "account.lookup",
				          "args": {"customerId": "cust_999"}
				        }
				      }]
				    }
				  }]
				}
				"""));

		GeminiModelProvider provider = provider();
		ModelDecision decision = provider.decideNextStep("gemini-2.0-flash", "Be helpful",
			List.of(message(ConversationMessageRole.USER, "find account")), List.of(
				new ToolDescriptor("tool-1", "account.lookup", "Look up account", "{\"type\":\"object\"}",
					com.actbrow.actbrow.model.ToolType.SERVER_BUILTIN, "accountLookup", Map.of(), Map.of())), 0);

		ToolCallDecision toolDecision = assertInstanceOf(ToolCallDecision.class, decision);
		assertEquals("account.lookup", toolDecision.toolCall().toolKey());
		assertEquals("cust_999", toolDecision.toolCall().arguments().get("customerId"));
	}

	private static GeminiModelProvider provider() {
		return new GeminiModelProvider(WebClient.builder(), new ObjectMapper().findAndRegisterModules(),
			new GeminiProperties("test-key", GEMINI_SERVER.url("/v1beta").toString().replaceAll("/$", ""),
				"gemini-2.0-flash", Duration.ofSeconds(5)));
	}

	private static ConversationMessageEntity message(ConversationMessageRole role, String content) {
		ConversationMessageEntity entity = new ConversationMessageEntity();
		entity.setRole(role);
		entity.setContent(content);
		return entity;
	}

	private static MockWebServer startServer() {
		try {
			MockWebServer server = new MockWebServer();
			server.start();
			return server;
		}
		catch (IOException exception) {
			throw new IllegalStateException("Unable to start mock Gemini server", exception);
		}
	}
}
