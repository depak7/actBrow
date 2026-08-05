package com.actbrow.actbrow.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import com.actbrow.actbrow.api.dto.ToolRequest;
import com.actbrow.actbrow.model.ToolType;

@Component
public class BuiltinToolCatalog {

	public List<ToolRequest> allBuiltInTools() {
		return Stream.concat(builtInClientTools().stream(), builtInServerTools().stream()).toList();
	}

	public List<ToolRequest> builtInServerTools() {
		return List.of(
			new ToolRequest("knowledge.search", "Knowledge Search",
				"Search operator-configured knowledge for this assistant (policies, product facts, SOPs, troubleshooting playbooks). "
					+ "Use ONLY when the user needs company or product information that is NOT visible on the current page and NOT "
					+ "available from other tools. Do NOT use for UI layout, buttons, labels, or page content — use PAGE_CONTEXT or "
					+ "page.observe for that (page.screenshot only for visual/canvas fallback). If no results are returned, tell the "
					+ "user you do not have that information; do not invent it.",
				Map.of(
					"type", "object",
					"properties", Map.of(
						"query", Map.of(
							"type", "string",
							"description", "What to search for in the knowledge base"),
						"path", Map.of(
							"type", "string",
							"description", "Optional current page path (e.g. /settings/billing) to prefer route-relevant documents")),
					"required", List.of("query")),
				null, ToolType.BUILD_IN, "1", true, "knowledge.search", Map.of(), Map.of()));
	}

	public List<ToolRequest> builtInClientTools() {
		return List.of(
			new ToolRequest("app.navigate", "App Navigate",
				"Navigate the user to a path inside the host app (e.g. /orders, /settings). Use this to move; do not use it to read. "
					+ "On success the result includes a pageObserve snapshot of the destination — do not call page.observe again "
					+ "in the same turn unless that snapshot is missing or clearly stale.",
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
			new ToolRequest("page.observe", "Page Observe",
				"Return a compact structured snapshot of the current page: interactive elements (ref, role, accessible name, "
					+ "selector), headings, path/url/title, and capped visibleText. Prefer this (and PAGE_CONTEXT on the user "
					+ "message) over page.screenshot for answering what is on screen. Call at most once per user turn, and skip "
					+ "if PAGE_CONTEXT or a recent navigate pageObserve already answers the question.",
				Map.of(
					"type", "object",
					"properties", Map.of()),
				null, ToolType.BUILD_IN, "1", true, "page.observe", Map.of(), Map.of()),
			new ToolRequest("page.screenshot", "Page Snapshot",
				"Vision/fallback page capture. In text mode this aliases page.observe (structured elements + visibleText). "
					+ "In image mode it returns a PNG of the viewport. Prefer PAGE_CONTEXT and page.observe first; use this only "
					+ "when structured observation is insufficient (canvas, icon-only UI without labels, visual layout questions). "
					+ "The 'visibleText' / elements fields are authoritative for what is on screen when returned.",
				Map.of(
					"type", "object",
					"properties", Map.of()),
				null, ToolType.BUILD_IN, "1", true, "page.screenshot", Map.of(), Map.of()));
	}
}
