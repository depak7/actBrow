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

import com.actbrow.actbrow.config.SarvamProperties;
import com.actbrow.actbrow.model.ConversationMessageEntity;
import com.actbrow.actbrow.model.ConversationMessageRole;
import com.fasterxml.jackson.databind.ObjectMapper;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

class SarvamModelProviderTests {

	private static final MockWebServer SARVAM_SERVER = startServer();

	@AfterAll
	static void shutdown() throws IOException {
		SARVAM_SERVER.shutdown();
	}

	@Test
	void mapsTextResponseToFinalDecision() {
		SARVAM_SERVER.enqueue(new MockResponse()
			.setHeader("Content-Type", "application/json")
			.setBody("""
				{
				  "choices": [{
				    "message": {
				      "content": "Final answer from Sarvam"
				    }
				  }]
				}
				"""));

		SarvamModelProvider provider = provider();
		ModelDecision decision = provider.decideNextStep("sarvam-m", "Be helpful",
			List.of(message(ConversationMessageRole.USER, "hello")), List.of(), 0);

		FinalResponseDecision finalResponse = assertInstanceOf(FinalResponseDecision.class, decision);
		assertEquals("Final answer from Sarvam", finalResponse.message());
	}

	@Test
	void mapsFunctionCallToToolDecision() {
		SARVAM_SERVER.enqueue(new MockResponse()
			.setHeader("Content-Type", "application/json")
			.setBody("""
				{
				  "choices": [{
				    "message": {
				      "tool_calls": [{
				        "function": {
				          "name": "account.lookup",
				          "arguments": "{\\"customerId\\":\\"cust_123\\"}"
				        }
				      }]
				    }
				  }]
				}
				"""));

		SarvamModelProvider provider = provider();
		ModelDecision decision = provider.decideNextStep("sarvam-m", "Be helpful",
			List.of(message(ConversationMessageRole.USER, "find account")), List.of(
				new ToolDescriptor("tool-1", "account.lookup", "Look up account", "{\"type\":\"object\"}",
					com.actbrow.actbrow.model.ToolType.SERVER_BUILTIN, "accountLookup", Map.of())), 0);

		ToolCallDecision toolDecision = assertInstanceOf(ToolCallDecision.class, decision);
		assertEquals("account.lookup", toolDecision.toolCall().toolKey());
		assertEquals("cust_123", toolDecision.toolCall().arguments().get("customerId"));
	}

	private static SarvamModelProvider provider() {
		return new SarvamModelProvider(WebClient.builder(), new ObjectMapper().findAndRegisterModules(),
			new SarvamProperties("test-key", SARVAM_SERVER.url("/v1").toString().replaceAll("/$", ""), "sarvam-m",
				Duration.ofSeconds(5)));
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
			throw new IllegalStateException("Unable to start mock Sarvam server", exception);
		}
	}
}
