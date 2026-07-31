package com.actbrow.actbrow.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Minimal MCP JSON-RPC client over HTTP (tools/list + tools/call).
 * Compatible with streamable-HTTP style endpoints that accept application/json.
 */
@Component
public class McpHttpClient {

	private final RestTemplate restTemplate = new RestTemplate();
	private final ObjectMapper objectMapper;
	private final AtomicLong idSeq = new AtomicLong(1);

	public McpHttpClient(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public void initialize(String serverUrl, Map<String, String> headers) {
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("protocolVersion", "2024-11-05");
		params.put("capabilities", Map.of());
		params.put("clientInfo", Map.of("name", "actbrow", "version", "1"));
		rpc(serverUrl, headers, "initialize", params, true);
		try {
			rpc(serverUrl, headers, "notifications/initialized", Map.of(), false);
		}
		catch (Exception ignored) {
			// Some servers ignore the initialized notification.
		}
	}

	@SuppressWarnings("unchecked")
	public List<McpToolInfo> listTools(String serverUrl, Map<String, String> headers) {
		initialize(serverUrl, headers);
		JsonNode result = rpc(serverUrl, headers, "tools/list", Map.of(), true);
		List<McpToolInfo> tools = new ArrayList<>();
		JsonNode toolsNode = result == null ? null : result.get("tools");
		if (toolsNode == null || !toolsNode.isArray()) {
			return tools;
		}
		for (JsonNode tool : toolsNode) {
			String name = text(tool, "name");
			if (name == null || name.isBlank()) {
				continue;
			}
			String description = text(tool, "description");
			Map<String, Object> inputSchema = Map.of("type", "object", "properties", Map.of());
			JsonNode schemaNode = tool.get("inputSchema");
			if (schemaNode != null && schemaNode.isObject()) {
				inputSchema = objectMapper.convertValue(schemaNode, Map.class);
			}
			tools.add(new McpToolInfo(name, description == null ? name : description, inputSchema));
		}
		return tools;
	}

	public String callTool(String serverUrl, Map<String, String> headers, String toolName,
		Map<String, Object> arguments) {
		initialize(serverUrl, headers);
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("name", toolName);
		params.put("arguments", arguments == null ? Map.of() : arguments);
		JsonNode result = rpc(serverUrl, headers, "tools/call", params, true);
		if (result == null) {
			return "{}";
		}
		return result.toString();
	}

	private JsonNode rpc(String serverUrl, Map<String, String> headers, String method, Object params,
		boolean expectResult) {
		try {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("jsonrpc", "2.0");
			if (expectResult) {
				body.put("id", idSeq.getAndIncrement());
			}
			body.put("method", method);
			if (params != null) {
				body.put("params", params);
			}

			HttpHeaders httpHeaders = new HttpHeaders();
			httpHeaders.setContentType(MediaType.APPLICATION_JSON);
			httpHeaders.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM));
			if (headers != null) {
				headers.forEach(httpHeaders::set);
			}

			ResponseEntity<String> response = restTemplate.postForEntity(serverUrl,
				new HttpEntity<>(objectMapper.writeValueAsString(body), httpHeaders), String.class);
			String raw = response.getBody();
			if (!expectResult) {
				return null;
			}
			if (raw == null || raw.isBlank()) {
				throw new IllegalStateException("Empty MCP response for " + method);
			}
			// Streamable HTTP may return SSE lines; extract the first JSON object.
			String json = extractJson(raw);
			JsonNode root = objectMapper.readTree(json);
			if (root.has("error") && !root.get("error").isNull()) {
				throw new IllegalStateException("MCP error on " + method + ": " + root.get("error"));
			}
			return root.get("result");
		}
		catch (IllegalStateException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new IllegalStateException("MCP " + method + " failed: " + ex.getMessage(), ex);
		}
	}

	private static String extractJson(String raw) {
		String trimmed = raw.trim();
		if (trimmed.startsWith("{")) {
			return trimmed;
		}
		// data: {...}
		for (String line : trimmed.split("\n")) {
			String t = line.trim();
			if (t.startsWith("data:")) {
				String payload = t.substring(5).trim();
				if (payload.startsWith("{")) {
					return payload;
				}
			}
			if (t.startsWith("{")) {
				return t;
			}
		}
		return trimmed;
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value == null || value.isNull() ? null : value.asText();
	}

	public record McpToolInfo(String name, String description, Map<String, Object> inputSchema) {
	}
}
