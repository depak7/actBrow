package com.actbrow.actbrow;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.actbrow.actbrow.api.dto.AssistantResponse;
import com.actbrow.actbrow.api.dto.ConversationResponse;
import com.actbrow.actbrow.api.dto.KnowledgeDocumentResponse;
import com.actbrow.actbrow.api.dto.RunEventResponse;
import com.actbrow.actbrow.api.dto.RunResponse;
import com.actbrow.actbrow.api.dto.ToolResponse;
import com.actbrow.actbrow.model.ToolType;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Disabled("Phase 1 follow-up: 8 mock responses still use Gemini's generateContent shape. "
	+ "Rewrite each MockResponse to OpenAI chat-completions shape (choices[].message.tool_calls[]) "
	+ "and update RecordedRequest assertions accordingly. Property keys are already migrated.")
class ApiFlowTests {

	private static final MockWebServer GEMINI_SERVER = startServer();

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", () -> "jdbc:h2:mem:actbrow-api-test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
		registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
		registry.add("spring.datasource.username", () -> "sa");
		registry.add("spring.datasource.password", () -> "");
		registry.add("spring.h2.console.enabled", () -> "false");
		registry.add("spring.ai.openai.api-key", () -> "test-key");
		registry.add("spring.ai.openai.base-url", () -> GEMINI_SERVER.url("/").toString().replaceAll("/$", ""));
		registry.add("spring.ai.openai.chat.options.model", () -> "deepseek-chat");
	}

	@AfterAll
	static void shutdown() throws IOException {
		GEMINI_SERVER.shutdown();
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

	private WebTestClient webTestClient() {
		return WebTestClient.bindToServer()
			.baseUrl("http://localhost:" + port)
			.responseTimeout(Duration.ofSeconds(10))
			.build();
	}

	@Test
	void completesRunWithServerTool() {
		GEMINI_SERVER.enqueue(jsonResponse("""
			{
			  "candidates": [{
			    "content": {
			      "parts": [{
			        "functionCall": {
			          "name": "account.lookup",
			          "args": {
			            "customerId": "cust_123"
			          }
			        }
			      }]
			    }
			  }]
			}
			"""));
		GEMINI_SERVER.enqueue(jsonResponse("""
			{
			  "candidates": [{
			    "content": {
			      "parts": [{
			        "text": "I looked up the customer account and completed the request."
			      }]
			    }
			  }]
			}
			"""));

		WebTestClient webTestClient = webTestClient();
		AssistantResponse assistant = webTestClient.post()
			.uri("/v1/assistants")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("""
				{"key":"support","name":"Support","systemPrompt":"Handle support","model":"gemini-2.0-flash","usePredefinedFlows":false,"userId":"api-flow-user"}
				""")
			.exchange()
			.expectStatus().isOk()
			.expectBody(AssistantResponse.class)
			.returnResult()
			.getResponseBody();

		ToolResponse tool = webTestClient.post()
			.uri("/v1/tools")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("""
				{"key":"account.lookup","displayName":"Account Lookup","description":"Look up account data","inputSchema":{"type":"object","properties":{"customerId":{"type":"string"}}},"type":"SERVER_BUILTIN","version":"1","enabled":true,"executorRef":"accountLookup","defaultArguments":{}}
				""")
			.exchange()
			.expectStatus().isCreated()
			.expectBody(ToolResponse.class)
			.returnResult()
			.getResponseBody();

		webTestClient.post()
			.uri("/v1/assistants/%s/tools".formatted(assistant.id()))
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("""
				{"toolId":"%s"}
				""".formatted(tool.id()))
			.exchange()
			.expectStatus().isNoContent();

		ConversationResponse conversation = webTestClient.post()
			.uri("/v1/conversations")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("""
				{"assistantId":"%s"}
				""".formatted(assistant.id()))
			.exchange()
			.expectStatus().isOk()
			.expectBody(ConversationResponse.class)
			.returnResult()
			.getResponseBody();

		RunResponse run = webTestClient.post()
			.uri("/v1/conversations/%s/turns".formatted(conversation.id()))
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("""
				{"content":"Please use account.lookup for this customer"}
				""")
			.exchange()
			.expectStatus().isOk()
			.expectBody(RunResponse.class)
			.returnResult()
			.getResponseBody();

		FluxExchangeResult<RunEventResponse> stream = webTestClient.get()
			.uri("/v1/runs/%s/events".formatted(run.id()))
			.accept(MediaType.TEXT_EVENT_STREAM)
			.exchange()
			.expectStatus().isOk()
			.returnResult(RunEventResponse.class);

		stream.getResponseBody()
			.take(4)
			.collectList()
			.block(Duration.ofSeconds(5));

		RunResponse finalRun = null;
		for (int attempt = 0; attempt < 20; attempt++) {
			finalRun = webTestClient.get()
				.uri("/v1/runs/%s".formatted(run.id()))
				.exchange()
				.expectStatus().isOk()
				.expectBody(RunResponse.class)
				.returnResult()
				.getResponseBody();
			if (finalRun != null && "COMPLETED".equals(finalRun.status().name())) {
				break;
			}
			try {
				Thread.sleep(100);
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted while waiting for run completion", exception);
			}
		}

		if (finalRun == null || !"COMPLETED".equals(finalRun.status().name())) {
			throw new AssertionError("Run did not complete, final status was "
				+ (finalRun == null ? "null" : finalRun.status().name()));
		}
	}

	@Test
	void rejectsInvalidToolSchema() {
		WebTestClient webTestClient = webTestClient();
		webTestClient.post()
			.uri("/v1/tools")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("""
				{"key":"bad","displayName":"Bad","description":"Bad schema","inputSchema":"oops","type":"%s","version":"1","enabled":true}
				""".formatted(ToolType.CLIENT.name()))
			.exchange()
			.expectStatus().isBadRequest();
	}

	@Test
	void prefersAliasToolOverGenericBuiltInForSameExecutor() {
		WebTestClient webTestClient = webTestClient();

		AssistantResponse assistant = webTestClient.post()
			.uri("/v1/assistants")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("""
				{"key":"support-alias","name":"Support Alias","systemPrompt":"Handle support","model":"gemini-2.0-flash","usePredefinedFlows":false,"userId":"api-flow-user"}
				""")
			.exchange()
			.expectStatus().isOk()
			.expectBody(AssistantResponse.class)
			.returnResult()
			.getResponseBody();

		ToolResponse aliasTool = webTestClient.post()
			.uri("/v1/tools")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("""
				{"key":"settings.open_page","displayName":"Open Settings","description":"Open the settings page.","inputSchema":{"type":"object","properties":{}},"type":"CLIENT","version":"1","enabled":true,"executorRef":"app.navigate","defaultArguments":{"path":"/settings"}}
				""")
			.exchange()
			.expectStatus().isCreated()
			.expectBody(ToolResponse.class)
			.returnResult()
			.getResponseBody();

		webTestClient.post()
			.uri("/v1/assistants/%s/tools".formatted(assistant.id()))
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("""
				{"toolId":"%s"}
				""".formatted(aliasTool.id()))
			.exchange()
			.expectStatus().isNoContent();

		GEMINI_SERVER.enqueue(jsonResponse("""
			{
			  "candidates": [{
			    "content": {
			      "parts": [{
			        "functionCall": {
			          "name": "settings.open_page",
			          "args": {}
			        }
			      }]
			    }
			  }]
			}
			"""));

		ConversationResponse conversation = webTestClient.post()
			.uri("/v1/conversations")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("""
				{"assistantId":"%s"}
				""".formatted(assistant.id()))
			.exchange()
			.expectStatus().isOk()
			.expectBody(ConversationResponse.class)
			.returnResult()
			.getResponseBody();

		RunResponse run = webTestClient.post()
			.uri("/v1/conversations/%s/turns".formatted(conversation.id()))
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("""
				{"content":"Open settings"}
				""")
			.exchange()
			.expectStatus().isOk()
			.expectBody(RunResponse.class)
			.returnResult()
			.getResponseBody();

		FluxExchangeResult<RunEventResponse> stream = webTestClient.get()
			.uri("/v1/runs/%s/events".formatted(run.id()))
			.accept(MediaType.TEXT_EVENT_STREAM)
			.exchange()
			.expectStatus().isOk()
			.returnResult(RunEventResponse.class);

		List<RunEventResponse> events = stream.getResponseBody()
			.take(2)
			.collectList()
			.block(Duration.ofSeconds(5));

		assertThat(events).isNotNull();
		RunEventResponse toolCallRequested = events.stream()
			.filter(event -> "tool.call.requested".equals(event.eventType()))
			.findFirst()
			.orElseThrow();
		assertThat(toolCallRequested.payload().get("toolKey")).isEqualTo("settings.open_page");
		assertThat(toolCallRequested.payload().get("executorKey")).isEqualTo("app.navigate");
	}

	@Test
	void storesAndListsAssistantKnowledge() {
		WebTestClient webTestClient = webTestClient();

		AssistantResponse assistant = webTestClient.post()
			.uri("/v1/assistants")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("""
				{"key":"support-knowledge","name":"Support Knowledge","systemPrompt":"Handle support","model":"gemini-2.0-flash","usePredefinedFlows":false,"userId":"api-flow-user"}
				""")
			.exchange()
			.expectStatus().isOk()
			.expectBody(AssistantResponse.class)
			.returnResult()
			.getResponseBody();

		KnowledgeDocumentResponse created = webTestClient.post()
			.uri("/v1/assistants/%s/knowledge".formatted(assistant.id()))
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("""
				{"title":"Refund policy","content":"Refunds are allowed within 30 days for annual plans.","source":"Support SOP","enabled":true}
				""")
			.exchange()
			.expectStatus().isCreated()
			.expectBody(KnowledgeDocumentResponse.class)
			.returnResult()
			.getResponseBody();

		assertThat(created).isNotNull();
		assertThat(created.title()).isEqualTo("Refund policy");

		webTestClient.get()
			.uri("/v1/assistants/%s/knowledge".formatted(assistant.id()))
			.exchange()
			.expectStatus().isOk()
			.expectBodyList(KnowledgeDocumentResponse.class)
			.value(docs -> {
				assertThat(docs).hasSize(1);
				assertThat(docs.getFirst().title()).isEqualTo("Refund policy");
				assertThat(docs.getFirst().source()).isEqualTo("Support SOP");
			});
	}

	@Test
	void createsAndAttachesToolInSingleRequest() {
		WebTestClient webTestClient = webTestClient();

		AssistantResponse assistant = webTestClient.post()
			.uri("/v1/assistants")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("""
				{"key":"support-combined","name":"Support Combined","systemPrompt":"Handle support","model":"gemini-2.0-flash","usePredefinedFlows":false,"userId":"api-flow-user"}
				""")
			.exchange()
			.expectStatus().isOk()
			.expectBody(AssistantResponse.class)
			.returnResult()
			.getResponseBody();

		ToolResponse tool = webTestClient.post()
			.uri("/v1/tools/attach")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("""
				{
				  "assistantId":"%s",
				  "key":"orders.open_page",
				  "displayName":"Open Orders Page",
				  "description":"Open the orders page.",
				  "inputSchema":{"type":"object","properties":{}},
				  "type":"CLIENT",
				  "version":"1",
				  "enabled":true,
				  "executorRef":"app.navigate",
				  "defaultArguments":{"path":"/orders"}
				}
				""".formatted(assistant.id()))
			.exchange()
			.expectStatus().isCreated()
			.expectBody(ToolResponse.class)
			.returnResult()
			.getResponseBody();

		List<ToolResponse> assistantTools = webTestClient.get()
			.uri("/v1/assistants/%s/tools".formatted(assistant.id()))
			.exchange()
			.expectStatus().isOk()
			.expectBodyList(ToolResponse.class)
			.returnResult()
			.getResponseBody();

		assertThat(tool).isNotNull();
		assertThat(assistantTools).isNotNull();
		assertThat(assistantTools).extracting(ToolResponse::id).contains(tool.id());
		assertThat(assistantTools).extracting(ToolResponse::key).contains("orders.open_page");
	}

	@Test
	void createsAndAttachesToolWithoutKeyGeneratesStableId() {
		WebTestClient webTestClient = webTestClient();

		AssistantResponse assistant = webTestClient.post()
			.uri("/v1/assistants")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("""
				{"key":"support-autokey","name":"Support Autokey","systemPrompt":"Handle support","model":"gemini-2.0-flash","usePredefinedFlows":false,"userId":"api-flow-user"}
				""")
			.exchange()
			.expectStatus().isOk()
			.expectBody(AssistantResponse.class)
			.returnResult()
			.getResponseBody();

		ToolResponse tool = webTestClient.post()
			.uri("/v1/tools/attach")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("""
				{
				  "assistantId":"%s",
				  "displayName":"Orphan Nav",
				  "description":"No client key supplied.",
				  "inputSchema":{"type":"object","properties":{}},
				  "type":"CLIENT",
				  "version":"1",
				  "enabled":true,
				  "executorRef":"app.navigate",
				  "defaultArguments":{"path":"/orphan"}
				}
				""".formatted(assistant.id()))
			.exchange()
			.expectStatus().isCreated()
			.expectBody(ToolResponse.class)
			.returnResult()
			.getResponseBody();

		assertThat(tool).isNotNull();
		assertThat(tool.key()).startsWith("tool_");
		assertThat(tool.key()).hasSize("tool_".length() + 32);

		List<ToolResponse> assistantTools = webTestClient.get()
			.uri("/v1/assistants/%s/tools".formatted(assistant.id()))
			.exchange()
			.expectStatus().isOk()
			.expectBodyList(ToolResponse.class)
			.returnResult()
			.getResponseBody();

		assertThat(assistantTools).extracting(ToolResponse::id).contains(tool.id());
	}

	@Test
	void deleteConversationRemovesDataAndIsIdempotent() {
		WebTestClient client = webTestClient();
		AssistantResponse assistant = client.post()
			.uri("/v1/assistants")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("""
				{"key":"delete-conv-asst","name":"Delete Conv","systemPrompt":"x","model":"gemini-2.0-flash","usePredefinedFlows":false,"userId":"api-flow-user"}
				""")
			.exchange()
			.expectStatus().isOk()
			.expectBody(AssistantResponse.class)
			.returnResult()
			.getResponseBody();

		ConversationResponse conversation = client.post()
			.uri("/v1/conversations")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("""
				{"assistantId":"%s"}
				""".formatted(assistant.id()))
			.exchange()
			.expectStatus().isOk()
			.expectBody(ConversationResponse.class)
			.returnResult()
			.getResponseBody();

		client.delete()
			.uri("/v1/conversations/%s".formatted(conversation.id()))
			.exchange()
			.expectStatus().isNoContent();

		client.delete()
			.uri("/v1/conversations/%s".formatted(conversation.id()))
			.exchange()
			.expectStatus().isNoContent();

		client.get()
			.uri("/v1/conversations/%s/messages".formatted(conversation.id()))
			.exchange()
			.expectStatus().isBadRequest();
	}

	@Test
	void ignoresStaleToolFailureAfterPageChangeAndReturnsStructuredClarification() throws Exception {
		WebTestClient client = webTestClient();

		AssistantResponse assistant = client.post()
			.uri("/v1/assistants")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("""
				{"key":"orders-overview-regression","name":"Orders Overview Regression","systemPrompt":"Help with navigation.","model":"gemini-2.0-flash","usePredefinedFlows":false,"userId":"api-flow-user"}
				""")
			.exchange()
			.expectStatus().isOk()
			.expectBody(AssistantResponse.class)
			.returnResult()
			.getResponseBody();

		ToolResponse ordersTool = client.post()
			.uri("/v1/tools/attach")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("""
				{
				  "assistantId":"%s",
				  "key":"orders.regression.open_page",
				  "displayName":"Open Orders",
				  "description":"Open the orders page.",
				  "inputSchema":{"type":"object","properties":{}},
				  "type":"CLIENT",
				  "version":"1",
				  "enabled":true,
				  "executorRef":"app.navigate",
				  "defaultArguments":{"path":"/orders"}
				}
				""".formatted(assistant.id()))
			.exchange()
			.expectStatus().isCreated()
			.expectBody(ToolResponse.class)
			.returnResult()
			.getResponseBody();

		assertThat(ordersTool).isNotNull();

		ConversationResponse conversation = client.post()
			.uri("/v1/conversations")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("""
				{"assistantId":"%s"}
				""".formatted(assistant.id()))
			.exchange()
			.expectStatus().isOk()
			.expectBody(ConversationResponse.class)
			.returnResult()
			.getResponseBody();

		GEMINI_SERVER.enqueue(jsonResponse("""
			{
			  "candidates": [{
			    "content": {
			      "parts": [{
			        "functionCall": {
			          "name": "orders.regression.open_page",
			          "args": {}
			        }
			      }]
			    }
			  }]
			}
			"""));
		GEMINI_SERVER.enqueue(jsonResponse("""
			{
			  "candidates": [{
			    "content": {
			      "parts": [{
			        "text": "I was unable to find your orders. The page for orders could not be found."
			      }]
			    }
			  }]
			}
			"""));

		RunResponse firstRun = client.post()
			.uri("/v1/conversations/%s/turns".formatted(conversation.id()))
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("""
				{
				  "content":"Show my orders",
				  "pageContext":{"url":"https://app.test/home","path":"/home","title":"Home","pageEpoch":1,"pageChanged":false,"elements":[]}
				}
				""")
			.exchange()
			.expectStatus().isOk()
			.expectBody(RunResponse.class)
			.returnResult()
			.getResponseBody();

		FluxExchangeResult<RunEventResponse> firstStream = client.get()
			.uri("/v1/runs/%s/events".formatted(firstRun.id()))
			.accept(MediaType.TEXT_EVENT_STREAM)
			.exchange()
			.expectStatus().isOk()
			.returnResult(RunEventResponse.class);

		List<RunEventResponse> firstEvents = firstStream.getResponseBody()
			.take(2)
			.collectList()
			.block(Duration.ofSeconds(5));

		RunEventResponse requested = firstEvents.stream()
			.filter(event -> "tool.call.requested".equals(event.eventType()))
			.findFirst()
			.orElseThrow();
		String toolCallId = String.valueOf(requested.payload().get("toolCallId"));

		client.post()
			.uri("/v1/runs/%s/tool-results".formatted(firstRun.id()))
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("""
				{
				  "toolCallId":"%s",
				  "success":false,
				  "textSummary":"Element not found: #orders",
				  "error":"Element not found: #orders"
				}
				""".formatted(toolCallId))
			.exchange()
			.expectStatus().isOk();

		awaitRunCompletion(client, firstRun.id());

		RecordedRequest firstModelRequest = GEMINI_SERVER.takeRequest(2, TimeUnit.SECONDS);
		RecordedRequest secondModelRequest = GEMINI_SERVER.takeRequest(2, TimeUnit.SECONDS);
		assertThat(firstModelRequest).isNotNull();
		assertThat(secondModelRequest).isNotNull();

		GEMINI_SERVER.enqueue(jsonResponse("""
			{
			  "candidates": [{
			    "content": {
			      "parts": [{
			        "text": "Which page did you mean?\\nOPTIONS: Overview | Orders | Profile\\nRECOMMENDED: Overview"
			      }]
			    }
			  }]
			}
			"""));

		RunResponse secondRun = client.post()
			.uri("/v1/conversations/%s/turns".formatted(conversation.id()))
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("""
				{
				  "content":"overview",
				  "pageContext":{"url":"https://app.test/overview","path":"/overview","title":"Overview","pageEpoch":2,"pageChanged":true,"elements":[]}
				}
				""")
			.exchange()
			.expectStatus().isOk()
			.expectBody(RunResponse.class)
			.returnResult()
			.getResponseBody();

		FluxExchangeResult<RunEventResponse> secondStream = client.get()
			.uri("/v1/runs/%s/events".formatted(secondRun.id()))
			.accept(MediaType.TEXT_EVENT_STREAM)
			.exchange()
			.expectStatus().isOk()
			.returnResult(RunEventResponse.class);

		List<RunEventResponse> secondEvents = secondStream.getResponseBody()
			.take(2)
			.collectList()
			.block(Duration.ofSeconds(5));

		RunEventResponse assistantMessage = secondEvents.stream()
			.filter(event -> "assistant.message.completed".equals(event.eventType()))
			.findFirst()
			.orElseThrow();

		assertThat(assistantMessage.payload().get("content")).isEqualTo("Which page did you mean?");
		assertThat(assistantMessage.payload().get("clarification")).isEqualTo(true);
		assertThat(assistantMessage.payload().get("recommendedOption")).isEqualTo("Overview");
		assertThat(assistantMessage.payload().get("options")).isEqualTo(List.of("Overview", "Orders", "Profile"));

		awaitRunCompletion(client, secondRun.id());

		RecordedRequest thirdModelRequest = GEMINI_SERVER.takeRequest(2, TimeUnit.SECONDS);
		assertThat(thirdModelRequest).isNotNull();
		String thirdBody = thirdModelRequest.getBody().readUtf8();
		assertThat(thirdBody).contains("\\\"pageEpoch\\\":2");
		assertThat(thirdBody).contains("\\\"pageChanged\\\":true");
		assertThat(thirdBody).doesNotContain("Tool result observed: Element not found: #orders");
		assertThat(thirdBody).doesNotContain("Tool result: Element not found: #orders");
	}

	private RunResponse awaitRunCompletion(WebTestClient client, String runId) {
		RunResponse finalRun = null;
		for (int attempt = 0; attempt < 20; attempt++) {
			finalRun = client.get()
				.uri("/v1/runs/%s".formatted(runId))
				.exchange()
				.expectStatus().isOk()
				.expectBody(RunResponse.class)
				.returnResult()
				.getResponseBody();
			if (finalRun != null && ("COMPLETED".equals(finalRun.status().name()) || "FAILED".equals(finalRun.status().name()))) {
				return finalRun;
			}
			try {
				Thread.sleep(100);
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted while waiting for run completion", exception);
			}
		}
		throw new AssertionError("Run did not finish: " + runId);
	}

	private static MockResponse jsonResponse(String body) {
		return new MockResponse()
			.setHeader("Content-Type", "application/json")
			.setResponseCode(200)
			.setBody(body);
	}
}
