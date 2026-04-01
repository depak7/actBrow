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
				null, ToolType.CLIENT, "1", true, "app.navigate", Map.of()),
			new ToolRequest("dom.click", "DOM Click",
				"Click an element in the current page using a CSS selector.",
				Map.of(
					"type", "object",
					"properties", Map.of("selector", Map.of("type", "string")),
					"required", List.of("selector")),
				null, ToolType.CLIENT, "1", true, "dom.click", Map.of()),
			new ToolRequest("dom.type", "DOM Type",
				"Type text into an element using a CSS selector and value.",
				Map.of(
					"type", "object",
					"properties", Map.of(
						"selector", Map.of("type", "string"),
						"value", Map.of("type", "string")),
					"required", List.of("selector", "value")),
				null, ToolType.CLIENT, "1", true, "dom.type", Map.of()),
			new ToolRequest("dom.read", "DOM Read",
				"Read text or value from an element using a CSS selector.",
				Map.of(
					"type", "object",
					"properties", Map.of("selector", Map.of("type", "string")),
					"required", List.of("selector")),
				null, ToolType.CLIENT, "1", true, "dom.read", Map.of()),
			new ToolRequest("dom.query", "DOM Query",
				"Find matching elements using a CSS selector.",
				Map.of(
					"type", "object",
					"properties", Map.of("selector", Map.of("type", "string")),
					"required", List.of("selector")),
				null, ToolType.CLIENT, "1", true, "dom.query", Map.of()),
			new ToolRequest("page.screenshot", "Page Screenshot",
				"Capture a page snapshot for observation.",
				Map.of(
					"type", "object",
					"properties", Map.of()),
				null, ToolType.CLIENT, "1", true, "page.screenshot", Map.of()));
	}
}
