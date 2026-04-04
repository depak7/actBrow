package com.actbrow.actbrow.agent;

import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Shapes {@link ToolDescriptor} fields for LLM requests (description text, etc.).
 */
public final class ModelToolPresentation {

	private ModelToolPresentation() {
	}

	/**
	 * Appends assistant-configured defaults so the model sees configured paths and params.
	 */
	public static String descriptionForModel(ToolDescriptor tool, ObjectMapper objectMapper) {
		String base = tool.description() == null ? "" : tool.description();
		Map<String, Object> defs = tool.defaultArguments();
		if (defs == null || defs.isEmpty()) {
			return base;
		}
		try {
			return base + "\n\nAssistant-configured defaults (use these when calling; dedicated nav tools keep this path): "
				+ objectMapper.writeValueAsString(defs);
		}
		catch (JsonProcessingException exception) {
			return base + "\n\nAssistant-configured defaults: " + defs;
		}
	}
}
