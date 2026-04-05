package com.actbrow.actbrow.service;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.actbrow.actbrow.api.dto.ToolRequest;
import com.actbrow.actbrow.model.ToolType;

@Component
public class BuiltinToolCatalog {

	public List<ToolRequest> builtInClientTools() {
		return List.of(
			new ToolRequest("app.navigate", "App Navigate",
				"Navigate within the current app. Use path values like /orders or /settings when the user asks to open a page.",
				Map.of(
					"type", "object",
					"properties", Map.of(
						"path", Map.of("type", "string"),
						"url", Map.of("type", "string"))),
				null, ToolType.BUILD_IN, "1", true, "app.navigate", Map.of(), Map.of()),
			new ToolRequest("dom.click", "DOM Click",
				"Click an element in the current page using a CSS selector.",
				Map.of(
					"type", "object",
					"properties", Map.of("selector", Map.of("type", "string")),
					"required", List.of("selector")),
				null, ToolType.BUILD_IN, "1", true, "dom.click", Map.of(), Map.of()),
			new ToolRequest("dom.type", "DOM Type",
				"Type text into an element using a CSS selector and value.",
				Map.of(
					"type", "object",
					"properties", Map.of(
						"selector", Map.of("type", "string"),
						"value", Map.of("type", "string")),
					"required", List.of("selector", "value")),
				null, ToolType.BUILD_IN, "1", true, "dom.type", Map.of(), Map.of()),
			new ToolRequest("dom.read", "DOM Read",
				"Read text or value from an element using a CSS selector.",
				Map.of(
					"type", "object",
					"properties", Map.of("selector", Map.of("type", "string")),
					"required", List.of("selector")),
				null, ToolType.BUILD_IN, "1", true, "dom.read", Map.of(), Map.of()),
			new ToolRequest("dom.query", "DOM Query",
				"Find matching elements using a CSS selector.",
				Map.of(
					"type", "object",
					"properties", Map.of("selector", Map.of("type", "string")),
					"required", List.of("selector")),
				null, ToolType.BUILD_IN, "1", true, "dom.query", Map.of(), Map.of()),
			new ToolRequest("page.screenshot", "Page Screenshot",
				"Capture a page snapshot for observation.",
				Map.of(
					"type", "object",
					"properties", Map.of()),
				null, ToolType.BUILD_IN, "1", true, "page.screenshot", Map.of(), Map.of()));
	}

	public List<ToolRequest> builtInHttpTools() {
		return List.of(
			new ToolRequest("api.get", "HTTP GET Request",
				"Make a GET request to an external API endpoint.",
				Map.of(
					"type", "object",
					"properties", Map.of(
						"url", Map.of("type", "string", "description", "Full URL or path"),
						"params", Map.of("type", "object", "description", "Query parameters"))),
				null, ToolType.BUILD_IN, "1", true, "api.get", Map.of(
					"method", "GET"), Map.of()),
			new ToolRequest("api.post", "HTTP POST Request",
				"Make a POST request to an external API endpoint.",
				Map.of(
					"type", "object",
					"properties", Map.of(
						"url", Map.of("type", "string", "description", "Full URL or path"),
						"body", Map.of("type", "object", "description", "Request body"))),
				null, ToolType.BUILD_IN, "1", true, "api.post", Map.of(
					"method", "POST",
					"headers", Map.of("Content-Type", "application/json")), Map.of()),
			new ToolRequest("api.put", "HTTP PUT Request",
				"Make a PUT request to an external API endpoint.",
				Map.of(
					"type", "object",
					"properties", Map.of(
						"url", Map.of("type", "string", "description", "Full URL or path"),
						"body", Map.of("type", "object", "description", "Request body"))),
				null, ToolType.BUILD_IN, "1", true, "api.put", Map.of(
					"method", "PUT",
					"headers", Map.of("Content-Type", "application/json")), Map.of()),
			new ToolRequest("api.delete", "HTTP DELETE Request",
				"Make a DELETE request to an external API endpoint.",
				Map.of(
					"type", "object",
					"properties", Map.of(
						"url", Map.of("type", "string", "description", "Full URL or path"))),
				null, ToolType.BUILD_IN, "1", true, "api.delete", Map.of(
					"method", "DELETE"), Map.of()));
	}
}
