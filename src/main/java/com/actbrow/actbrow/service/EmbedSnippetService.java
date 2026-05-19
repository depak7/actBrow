package com.actbrow.actbrow.service;

import org.springframework.stereotype.Service;

@Service
public class EmbedSnippetService {

	public String buildSnippet(String baseUrl, String assistantId, String widgetKey) {
		String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
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
			  }
			};
			</script>
			<script src="%s/actbrow-widget.js"></script>
			""".formatted(normalizedBase, assistantId, normalizedBase, widgetKey, normalizedBase);
	}
}
