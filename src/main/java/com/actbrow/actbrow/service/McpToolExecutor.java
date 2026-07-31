package com.actbrow.actbrow.service;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.actbrow.actbrow.agent.ToolDescriptor;
import com.actbrow.actbrow.agent.ToolExecutionResult;
import com.actbrow.actbrow.api.NotFoundException;
import com.actbrow.actbrow.model.McpServerEntity;
import com.actbrow.actbrow.repository.McpServerRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class McpToolExecutor {

	private final McpServerRepository mcpServerRepository;
	private final McpHttpClient mcpHttpClient;
	private final McpServerService mcpServerService;
	private final OutboundUrlPolicy outboundUrlPolicy;
	private final ObjectMapper objectMapper;

	public McpToolExecutor(McpServerRepository mcpServerRepository, McpHttpClient mcpHttpClient,
		McpServerService mcpServerService, OutboundUrlPolicy outboundUrlPolicy, ObjectMapper objectMapper) {
		this.mcpServerRepository = mcpServerRepository;
		this.mcpHttpClient = mcpHttpClient;
		this.mcpServerService = mcpServerService;
		this.outboundUrlPolicy = outboundUrlPolicy;
		this.objectMapper = objectMapper;
	}

	public ToolExecutionResult execute(ToolDescriptor tool, Map<String, Object> arguments) {
		try {
			Map<String, Object> metadata = tool.metadata() == null ? Map.of() : tool.metadata();
			String serverId = String.valueOf(metadata.getOrDefault("mcpServerId", ""));
			String mcpToolName = String.valueOf(metadata.getOrDefault("mcpToolName", ""));
			if (serverId.isBlank() || mcpToolName.isBlank()) {
				return new ToolExecutionResult(false, null, "MCP tool metadata incomplete",
					"mcpServerId/mcpToolName required");
			}
			McpServerEntity server = mcpServerRepository.findById(serverId)
				.orElseThrow(() -> new NotFoundException("MCP server not found"));
			if (!server.isEnabled()) {
				return new ToolExecutionResult(false, null, "MCP server disabled", "Server is disabled");
			}
			outboundUrlPolicy.validateHttpUrl(server.getServerUrl());
			Map<String, String> headers = mcpServerService.authHeadersFor(server);
			outboundUrlPolicy.validateHeaders(headers);
			String result = mcpHttpClient.callTool(server.getServerUrl(), headers, mcpToolName, arguments);
			// MCP reports tool-level failures via isError on the result, not a JSON-RPC error.
			if (isErrorResult(result)) {
				return new ToolExecutionResult(false, result, "MCP tool " + mcpToolName + " failed",
					"MCP tool " + mcpToolName + " reported an error: " + errorExcerpt(result));
			}
			return new ToolExecutionResult(true, result, "MCP tool " + mcpToolName + " completed", null);
		}
		catch (Exception exception) {
			return new ToolExecutionResult(false, null, "MCP tool failed", exception.getMessage());
		}
	}

	private boolean isErrorResult(String result) {
		JsonNode root = parse(result);
		return root != null && root.path("isError").asBoolean(false);
	}

	/** Compact text excerpt from the result content so FailureClassifier can match on it. */
	private String errorExcerpt(String result) {
		JsonNode root = parse(result);
		if (root == null) {
			return "unknown error";
		}
		StringBuilder text = new StringBuilder();
		for (JsonNode item : root.path("content")) {
			String value = item.path("text").asText("");
			if (!value.isBlank()) {
				if (text.length() > 0) {
					text.append(' ');
				}
				text.append(value.trim());
			}
		}
		if (text.length() == 0) {
			return "unknown error";
		}
		return text.length() > 500 ? text.substring(0, 500) : text.toString();
	}

	private JsonNode parse(String result) {
		if (result == null || result.isBlank()) {
			return null;
		}
		try {
			return objectMapper.readTree(result);
		}
		catch (Exception ignored) {
			return null;
		}
	}
}
