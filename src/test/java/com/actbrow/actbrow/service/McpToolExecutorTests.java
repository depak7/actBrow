package com.actbrow.actbrow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.actbrow.actbrow.agent.ToolDescriptor;
import com.actbrow.actbrow.agent.ToolExecutionResult;
import com.actbrow.actbrow.model.FailureType;
import com.actbrow.actbrow.model.McpServerEntity;
import com.actbrow.actbrow.model.ToolType;
import com.actbrow.actbrow.repository.McpServerRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

class McpToolExecutorTests {

	@Mock
	private McpServerRepository mcpServerRepository;

	@Mock
	private McpHttpClient mcpHttpClient;

	@Mock
	private McpServerService mcpServerService;

	private McpToolExecutor executor;
	private final FailureClassifier classifier = new FailureClassifier();

	private final ToolDescriptor tool = new ToolDescriptor("tool-1", "mcp.search", "Search", "{}",
		ToolType.MCP, "mcp.search", Map.of(),
		Map.of("mcpServerId", "server-1", "mcpToolName", "search"));

	@BeforeEach
	void setUp() {
		MockitoAnnotations.openMocks(this);
		OutboundUrlPolicy permissivePolicy = new OutboundUrlPolicy(false, "") {
			@Override
			public URI validateHttpUrl(String rawUrl) {
				return URI.create(rawUrl.trim());
			}
		};
		executor = new McpToolExecutor(mcpServerRepository, mcpHttpClient, mcpServerService,
			permissivePolicy, new ObjectMapper());

		McpServerEntity server = new McpServerEntity();
		server.setId("server-1");
		server.setServerUrl("https://mcp.example.com/mcp");
		server.setEnabled(true);
		when(mcpServerRepository.findById("server-1")).thenReturn(Optional.of(server));
		when(mcpServerService.authHeadersFor(any())).thenReturn(Map.of());
	}

	@Test
	void plainResultIsSuccess() {
		when(mcpHttpClient.callTool(anyString(), anyMap(), anyString(), anyMap()))
			.thenReturn("{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}");

		ToolExecutionResult result = executor.execute(tool, Map.of("q", "shoes"));

		assertThat(result.success()).isTrue();
		assertThat(result.structuredOutput()).contains("ok");
		assertThat(result.error()).isNull();
	}

	@Test
	void isErrorResultMapsToFailureWithContentTextInError() {
		when(mcpHttpClient.callTool(anyString(), anyMap(), anyString(), anyMap()))
			.thenReturn("{\"content\":[{\"type\":\"text\",\"text\":\"resource not found (404)\"}],\"isError\":true}");

		ToolExecutionResult result = executor.execute(tool, Map.of("q", "shoes"));

		assertThat(result.success()).isFalse();
		assertThat(result.textSummary()).contains("failed");
		assertThat(result.error()).contains("resource not found (404)");
		assertThat(result.structuredOutput()).contains("isError");
		assertThat(classifier.classify(tool, result)).isEqualTo(FailureType.NOT_FOUND);
	}

	@Test
	void isErrorWithoutTextStillFails() {
		when(mcpHttpClient.callTool(anyString(), anyMap(), anyString(), anyMap()))
			.thenReturn("{\"content\":[],\"isError\":true}");

		ToolExecutionResult result = executor.execute(tool, Map.of());

		assertThat(result.success()).isFalse();
		assertThat(result.error()).contains("unknown error");
	}

	@Test
	void jsonRpcErrorExceptionMapsToFailure() {
		when(mcpHttpClient.callTool(anyString(), anyMap(), anyString(), anyMap()))
			.thenThrow(new IllegalStateException("MCP error on tools/call: {\"code\":-32602,\"message\":\"invalid params\"}"));

		ToolExecutionResult result = executor.execute(tool, Map.of());

		assertThat(result.success()).isFalse();
		assertThat(result.error()).contains("invalid params");
		assertThat(classifier.classify(tool, result)).isEqualTo(FailureType.INVALID_ARGUMENTS);
	}
}
