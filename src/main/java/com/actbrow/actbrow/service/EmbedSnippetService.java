package com.actbrow.actbrow.service;

import java.util.Map;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class EmbedSnippetService {

	private final ObjectMapper objectMapper;

	public EmbedSnippetService(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public String buildSnippet(String baseUrl, String assistantId, String widgetKey) {
		return buildSnippet(baseUrl, assistantId, widgetKey, null);
	}

	public String buildSnippet(String baseUrl, String assistantId, String widgetKey, Map<String, Object> theme) {
		String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
		String themeBlock = "";
		if (theme != null && !theme.isEmpty()) {
			try {
				themeBlock = ",\n			  theme: " + objectMapper.writeValueAsString(theme);
			}
			catch (Exception ignored) {
				themeBlock = "";
			}
		}
		return """
			<script src="%s/actbrow-sdk.js"></script>
			<script>
			window.ActbrowWidgetConfig = {
			  assistantId: "%s",
			  baseUrl: "%s",
			  apiKey: "%s",
			  navigate: function (path) {
			    // Wire your SPA router here, e.g. router.push(path)
			    window.location.assign(path);
			  }%s
			};
			</script>
			<script src="%s/actbrow-widget.js"></script>
			""".formatted(normalizedBase, assistantId, normalizedBase, widgetKey, themeBlock, normalizedBase);
	}
}
