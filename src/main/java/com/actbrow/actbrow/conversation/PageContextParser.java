package com.actbrow.actbrow.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Extracts fields from the PAGE_CONTEXT JSON appendix on stored user messages.
 */
public final class PageContextParser {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private PageContextParser() {
	}

	public static String extractPath(String storedUserContent) {
		JsonNode root = parsePageContextJson(storedUserContent);
		if (root == null) {
			return null;
		}
		JsonNode path = root.get("path");
		if (path == null || path.isNull()) {
			return null;
		}
		String value = path.asText("").trim();
		return value.isEmpty() ? null : value;
	}

	private static JsonNode parsePageContextJson(String storedUserContent) {
		if (storedUserContent == null || storedUserContent.isEmpty()) {
			return null;
		}
		int appendix = storedUserContent.indexOf(UserMessageDisplay.PAGE_CONTEXT_APPENDIX_START);
		if (appendix < 0) {
			return null;
		}
		int jsonStart = storedUserContent.indexOf("---\n", appendix);
		if (jsonStart < 0) {
			return null;
		}
		jsonStart += 4;
		String json = storedUserContent.substring(jsonStart).trim();
		int truncated = json.indexOf("\n...(PAGE_CONTEXT truncated)");
		if (truncated >= 0) {
			json = json.substring(0, truncated).trim();
		}
		if (json.isEmpty()) {
			return null;
		}
		try {
			return OBJECT_MAPPER.readTree(json);
		}
		catch (Exception exception) {
			return null;
		}
	}
}
