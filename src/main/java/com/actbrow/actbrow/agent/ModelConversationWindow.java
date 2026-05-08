package com.actbrow.actbrow.agent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.actbrow.actbrow.model.ConversationMessageEntity;
import com.actbrow.actbrow.model.ConversationMessageRole;

/**
 * Shapes conversation history for model calls so current-turn grounding is preserved while
 * stale tool outcomes from older turns do not dominate the next decision.
 */
public final class ModelConversationWindow {

	private static final Pattern TOOL_CALL_ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");

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
		return content != null && content.startsWith("[tool_calls]");
	}

	private static List<String> extractToolCallIds(String content) {
		List<String> ids = new ArrayList<>();
		Matcher matcher = TOOL_CALL_ID.matcher(content);
		while (matcher.find()) {
			ids.add(matcher.group(1));
		}
		return ids;
	}
}
