package com.actbrow.actbrow.service;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.actbrow.actbrow.model.AssistantDefinitionEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class AssistantSetupPromptService {

	private final ObjectMapper objectMapper;

	public AssistantSetupPromptService(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public String buildSetupPrompt(AssistantDefinitionEntity assistant, String baseUrl, String setupKey) {
		String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
		return """
			You are configuring Actbrow for this repository. Actbrow is an embeddable AI assistant that can navigate the host app and call HTTP tools.

			## Credentials (do not commit these to git)
			- ACTBROW_BASE_URL=%s
			- ACTBROW_ASSISTANT_ID=%s
			- ACTBROW_SETUP_KEY=%s

			## Your job
			1. Scan this repo: frontend routes, API handlers or OpenAPI spec, README/support docs, and the main layout file.
			2. Infer a focused assistant system prompt for this product.
			3. Build navigation tools for important app routes (path + display name).
			4. Build HTTP tools from OpenAPI or backend routes. Every HTTP tool's metadata MUST include:
			   - method, baseUrl, path
			   - execution: "browser" for same-origin calls that should reuse the signed-in user's session cookies, otherwise "server"
			   - parameters: OpenAPI-style [{ "name": "...", "in": "path"|"query"|"header" }] for EVERY argument that is not a JSON body field.
			     Arguments missing from this list are sent as the request body, so a path like /orders/{id} would keep the literal {id}.
			   - sideEffectLevel: "READ" for GET/HEAD, "WRITE" for POST/PUT/PATCH, "DESTRUCTIVE" for DELETE or anything irreversible (payments, cancellations).
			     This drives shadow mode and post-action verification — a mislabeled write bypasses both.
			   - idempotent: true for GET/PUT/DELETE, false for POST unless the endpoint dedupes.
			5. Add 2-5 knowledge documents from product docs or README.
			6. Optionally add navigation flows for common multi-step journeys.
			7. Push everything live with ONE request:

			PUT %s/v1/assistants/%s/sync
			Authorization: Bearer %s
			Content-Type: application/json

			Body shape:
			{
			  "assistant": { "systemPrompt": "...", "usePredefinedFlows": true },
			  "origins": ["http://localhost:3000", "https://app.example.com"],
			  "navigation": [{ "key": "billing.open", "path": "/settings/billing", "displayName": "Open Billing" }],
			  "httpTools": [{
			    "key": "orders.create",
			    "displayName": "Create Order",
			    "description": "...",
			    "inputSchema": { "type": "object", "properties": { "sku": { "type": "string" } } },
			    "metadata": {
			      "method": "POST", "baseUrl": "https://api.example.com", "path": "/orders", "execution": "server",
			      "parameters": [{ "name": "warehouseId", "in": "query" }],
			      "sideEffectLevel": "WRITE", "idempotent": false
			    }
			  }],
			  "flows": [{ "name": "View billing", "triggerPhrase": "billing|invoice", "enabled": true, "steps": [{ "action": "navigate", "target": "billing.open" }] }],
			  "knowledge": [{ "title": "Refund policy", "content": "...", "enabled": true }]
			}

			8. After sync succeeds, add the returned embedSnippet to the app layout and wire navigate to the SPA router (Next.js router.push, React Router navigate, etc.).
			9. Verify before you finish: run the app, open the widget, and try one navigation request and one HTTP-tool request. If a tool fails, fix the metadata and re-run the sync — a tool that never worked is worse than one you did not add.
			10. Do NOT hand-configure tools in the Actbrow dashboard — push via sync API. The dashboard is for review only. (MCP servers are the exception: they are connected in the dashboard, not through this sync payload.)

			## Rules
			- Use stable tool keys (dot.case).
			- Navigation tools use executor app.navigate via sync payload defaults (path in defaultArguments).
			- Include EVERY origin the app is served from — local dev and production. A missing origin blocks the widget with a CORS error.
			- Only include HTTP tools the app actually exposes.
			- Do not create tools for path.find, page.screenshot, app.navigate or knowledge.search — they are built in and always available.
			- Keep knowledge concise and operational.
			- When done, summarize what you pushed, where you added the embed snippet, and what you verified in step 9.
			""".formatted(
			normalizedBase,
			assistant.getId(),
			setupKey,
			normalizedBase,
			assistant.getId(),
			setupKey);
	}

	public List<String> parseOrigins(AssistantDefinitionEntity assistant) {
		if (assistant.getAllowedOriginsJson() == null || assistant.getAllowedOriginsJson().isBlank()) {
			return List.of();
		}
		try {
			return objectMapper.readValue(assistant.getAllowedOriginsJson(), new TypeReference<List<String>>() {
			});
		}
		catch (Exception exception) {
			return List.of();
		}
	}

	public Map<String, Object> parseSummary(AssistantDefinitionEntity assistant) {
		if (assistant.getLastSyncSummaryJson() == null || assistant.getLastSyncSummaryJson().isBlank()) {
			return Map.of();
		}
		try {
			return objectMapper.readValue(assistant.getLastSyncSummaryJson(), new TypeReference<Map<String, Object>>() {
			});
		}
		catch (Exception exception) {
			return Map.of();
		}
	}
}
