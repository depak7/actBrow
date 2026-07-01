package com.actbrow.actbrow.agent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.actbrow.actbrow.model.ConversationMessageEntity;
import com.actbrow.actbrow.model.ConversationMessageRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Shapes conversation history for model calls so current-turn grounding is preserved while
 * stale tool outcomes from older turns do not dominate the next decision.
 */
public final class ModelConversationWindow {

	private static final String OPEN_MARKER = "[tool_calls]";
	private static final String CLOSE_MARKER = "[/tool_calls]";
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private ModelConversationWindow() {
	}

	public static List<ConversationMessageEntity> forModel(List<ConversationMessageEntity> messages) {
		int latestUserIndex = -1;
		for (int index = 0; index < messages.size(); index++) {
			if (messages.get(index).getRole() == ConversationMessageRole.USER) {
				latestUserIndex = index;
			}
		}
		if (latestUserIndex < 0) {
			return messages;
		}
		// Build satisfied-id set from TOOL messages in the CURRENT turn (after latest USER) only.
		// Any ASSISTANT tool_calls older than the latest USER is treated as historical and dropped
		// together with its (also-dropped) TOOL response — otherwise we'd leave orphan tool_calls
		// that OpenAI rejects with "messages with role 'tool' must be a response to a preceding
		// message with 'tool_calls'".
		Set<String> currentTurnSatisfiedIds = new HashSet<>();
		for (int index = latestUserIndex + 1; index < messages.size(); index++) {
			ConversationMessageEntity message = messages.get(index);
			if (message.getRole() == ConversationMessageRole.TOOL && message.getToolCallId() != null) {
				currentTurnSatisfiedIds.add(message.getToolCallId());
			}
		}
		List<ConversationMessageEntity> filtered = new ArrayList<>(messages.size());
		for (int index = 0; index < messages.size(); index++) {
			ConversationMessageEntity message = messages.get(index);
			// Drop every TOOL message before the latest USER (stale tool outputs should not dominate).
			if (message.getRole() == ConversationMessageRole.TOOL && index <= latestUserIndex) {
				continue;
			}
			if (message.getRole() == ConversationMessageRole.ASSISTANT && isToolCallsEnvelope(message.getContent())) {
				// Before latest USER: paired TOOL was just dropped — drop this assistant too.
				if (index <= latestUserIndex) {
					continue;
				}
				// After latest USER: drop if any call id has no matching TOOL in the current turn
				// (guards against a hard refresh mid-turn leaving a dangling tool_calls entry).
				List<String> callIds = extractToolCallIds(message.getContent());
				if (!callIds.isEmpty() && !currentTurnSatisfiedIds.containsAll(callIds)) {
					continue;
				}
			}
			filtered.add(message);
		}
		return filtered;
	}

	private static boolean isToolCallsEnvelope(String content) {
		return content != null && content.startsWith(OPEN_MARKER);
	}

	/**
	 * Parse the top-level {@code id} of each tool call in a {@code [tool_calls][...][/tool_calls]}
	 * envelope. Parses the JSON array so an {@code id} field nested inside a call's
	 * {@code function.arguments} is never mistaken for a tool-call id. On any parse failure we
	 * return an empty list, which keeps the message (better than dropping a legitimate turn).
	 */
	private static List<String> extractToolCallIds(String content) {
		List<String> ids = new ArrayList<>();
		if (content == null) {
			return ids;
		}
		String json = content;
		int open = json.indexOf(OPEN_MARKER);
		if (open >= 0) {
			json = json.substring(open + OPEN_MARKER.length());
		}
		int close = json.lastIndexOf(CLOSE_MARKER);
		if (close >= 0) {
			json = json.substring(0, close);
		}
		try {
			JsonNode array = MAPPER.readTree(json);
			if (array.isArray()) {
				for (JsonNode call : array) {
					JsonNode id = call.get("id");
					if (id != null && id.isTextual()) {
						ids.add(id.asText());
					}
				}
			}
		}
		catch (Exception ignored) {
			// Malformed envelope — treat as no ids so the message is retained rather than dropped.
		}
		return ids;
	}
}
