package com.actbrow.actbrow.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.actbrow.actbrow.agent.ToolDescriptor;
import com.actbrow.actbrow.agent.ToolExecutionResult;
import com.actbrow.actbrow.model.FailureType;
import com.actbrow.actbrow.model.ToolType;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

class HttpServerToolExecutorTests {

	private MockWebServer server;
	private HttpServerToolExecutor executor;
	private final FailureClassifier classifier = new FailureClassifier();

	/** MockWebServer binds to loopback, which the real policy blocks; tests bypass validation. */
	private static final OutboundUrlPolicy PERMISSIVE_POLICY = new OutboundUrlPolicy(false, "") {
		@Override
		public URI validateHttpUrl(String rawUrl) {
			return URI.create(rawUrl.trim());
		}
	};

	@BeforeEach
	void setUp() throws IOException {
		server = new MockWebServer();
		server.start();
		executor = new HttpServerToolExecutor(PERMISSIVE_POLICY);
	}

	@AfterEach
	void tearDown() throws IOException {
		server.shutdown();
	}

	private ToolDescriptor tool(String method, String path) {
		String baseUrl = server.url("").toString();
		// Strip the trailing slash so baseUrl + path does not double up.
		baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
		return new ToolDescriptor("tool-1", "orders.fetch", "Fetch order", "{}",
			ToolType.SERVER_HTTP, "orders.fetch", Map.of(),
			Map.of("baseUrl", baseUrl, "method", method, "path", path));
	}

	@Test
	void twoHundredIsSuccessWithStatusInSummary() {
		server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"id\":7}"));

		ToolExecutionResult result = executor.execute(tool("GET", "/orders/7"), Map.of());

		assertThat(result.success()).isTrue();
		assertThat(result.structuredOutput()).isEqualTo("{\"id\":7}");
		assertThat(result.textSummary()).contains("200");
		assertThat(result.error()).isNull();
	}

	@Test
	void fourOhFourIsFailureWithStatusAndBodyPreserved() {
		server.enqueue(new MockResponse().setResponseCode(404).setBody("{\"message\":\"order not found\"}"));

		ToolExecutionResult result = executor.execute(tool("GET", "/orders/999"), Map.of());

		assertThat(result.success()).isFalse();
		assertThat(result.structuredOutput()).isEqualTo("{\"message\":\"order not found\"}");
		assertThat(result.textSummary()).contains("404");
		assertThat(result.error()).contains("404").contains("order not found");
		assertThat(classifier.classify(tool("GET", "/orders/999"), result)).isEqualTo(FailureType.NOT_FOUND);
	}

	@Test
	void fiveHundredIsFailureWithStatusInError() {
		server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));

		ToolExecutionResult result = executor.execute(tool("POST", "/orders"), Map.of("sku", "A1"));

		assertThat(result.success()).isFalse();
		assertThat(result.structuredOutput()).isEqualTo("boom");
		assertThat(result.textSummary()).contains("500");
		assertThat(result.error()).contains("500");
		assertThat(classifier.classify(tool("POST", "/orders"), result)).isEqualTo(FailureType.SERVER_ERROR);
	}

	@Test
	void errorBodyExcerptIsCappedButFullBodyKeptInStructuredOutput() {
		String body = "x".repeat(2_000);
		server.enqueue(new MockResponse().setResponseCode(400).setBody(body));

		ToolExecutionResult result = executor.execute(tool("GET", "/orders"), Map.of());

		assertThat(result.success()).isFalse();
		assertThat(result.structuredOutput()).hasSize(2_000);
		assertThat(result.error()).contains("400");
		assertThat(result.error().length()).isLessThanOrEqualTo(600);
	}

	@Test
	void oversizedBodyIsRejectedForFailureResponsesToo() {
		server.enqueue(new MockResponse().setResponseCode(500).setBody("y".repeat(250_001)));

		ToolExecutionResult result = executor.execute(tool("GET", "/orders"), Map.of());

		assertThat(result.success()).isFalse();
		assertThat(result.structuredOutput()).isNull();
		assertThat(result.error()).contains("250KB");
	}
}
