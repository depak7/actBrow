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
			4. Build HTTP tools from OpenAPI or backend routes (method, baseUrl, path, inputSchema, metadata.execution=browser for same-origin browser calls).
			5. Add 2-5 knowledge documents from product docs or README.
			6. Optionally add navigation flows for common multi-step journeys.
			7. Push everything live with ONE request:

			PUT %s/v1/assistants/%s/sync
			Authorization: Bearer %s
			Content-Type: application/json

			Body shape:
			{
			  "assistant": { "systemPrompt": "...", "usePredefinedFlows": true },
			  "origins": ["http://localhost:3000"],
			  "navigation": [{ "key": "billing.open", "path": "/settings/billing", "displayName": "Open Billing" }],
			  "httpTools": [{
			    "key": "orders.create",
			    "displayName": "Create Order",
			    "description": "...",
			    "inputSchema": { "type": "object", "properties": { "sku": { "type": "string" } } },
			    "metadata": { "method": "POST", "baseUrl": "https://api.example.com", "path": "/orders", "execution": "server" }
			  }],
			  "flows": [{ "name": "View billing", "triggerPhrase": "billing|invoice", "enabled": true, "steps": [{ "action": "navigate", "target": "billing.open" }] }],
			  "knowledge": [{ "title": "Refund policy", "content": "...", "enabled": true }]
			}

			8. After sync succeeds, add the returned embedSnippet to the app layout and wire navigate to the SPA router (Next.js router.push, React Router navigate, etc.).
			9. Do NOT hand-configure tools in the Actbrow dashboard — push via sync API. The dashboard is for review only.

			## Rules
			- Use stable tool keys (dot.case).
			- Navigation tools use executor app.navigate via sync payload defaults (path in defaultArguments).
			- Only include HTTP tools the app actually exposes.
			- Keep knowledge concise and operational.
			- When done, summarize what you pushed and where you added the embed snippet.
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
