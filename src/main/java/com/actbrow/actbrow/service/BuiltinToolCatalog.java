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
				"Navigate the user to a path inside the host app (e.g. /orders, /settings). Use this to move; do not use it to read.",
				Map.of(
					"type", "object",
					"properties", Map.of(
						"path", Map.of("type", "string"),
						"url", Map.of("type", "string"))),
				null, ToolType.BUILD_IN, "1", true, "app.navigate", Map.of(), Map.of()),
			new ToolRequest("path.find", "Current Location",
				"Return the user's current location in the host app: path, full URL, page title, and any query/hash. Use this to know where the user is before suggesting a navigation or operation.",
				Map.of(
					"type", "object",
					"properties", Map.of()),
				null, ToolType.BUILD_IN, "1", true, "path.find", Map.of(), Map.of()),
			new ToolRequest("page.screenshot", "Page Snapshot",
				"Return the visible text of the current page (document.body.innerText, capped at 12000 chars), plus path/url/title. The 'visibleText' field is the AUTHORITATIVE list of what is on screen — text that does not appear there is not on the page. Use only to answer questions about what the user is currently looking at.",
				Map.of(
					"type", "object",
					"properties", Map.of()),
				null, ToolType.BUILD_IN, "1", true, "page.screenshot", Map.of(), Map.of()));
	}
}
