package com.actbrow.actbrow.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.actbrow.actbrow.model.ConversationMessageEntity;
import com.actbrow.actbrow.model.ConversationMessageRole;

class ModelConversationWindowTests {

	@Test
	void dropsOrphanAssistantToolCallsWithNoMatchingToolMessage() {
		ConversationMessageEntity user1 = message(ConversationMessageRole.USER, "show my orders", null);
		ConversationMessageEntity orphan = message(ConversationMessageRole.ASSISTANT,
			"[tool_calls][{\"id\":\"call_lost\",\"type\":\"function\",\"function\":{\"name\":\"app_navigate\"}}][/tool_calls]",
			null);
		ConversationMessageEntity user2 = message(ConversationMessageRole.USER, "proceed to shipping", null);

		List<ConversationMessageEntity> result = ModelConversationWindow.forModel(List.of(user1, orphan, user2));

		assertEquals(2, result.size());
		assertFalse(result.contains(orphan), "Orphan assistant tool_calls must be dropped");
	}

	@Test
	void keepsAssistantToolCallsWhenAllIdsHaveMatchingToolMessages() {
		ConversationMessageEntity user1 = message(ConversationMessageRole.USER, "navigate", null);
		ConversationMessageEntity assistant = message(ConversationMessageRole.ASSISTANT,
			"[tool_calls][{\"id\":\"call_ok\",\"type\":\"function\",\"function\":{\"name\":\"app_navigate\"}}][/tool_calls]",
			null);
		ConversationMessageEntity tool = message(ConversationMessageRole.TOOL, "Navigated", "call_ok");
		ConversationMessageEntity user2 = message(ConversationMessageRole.USER, "next", null);

		List<ConversationMessageEntity> result = ModelConversationWindow
			.forModel(List.of(user1, assistant, tool, user2, message(ConversationMessageRole.ASSISTANT, "done", null)));

		// When paired but older than latest USER, both are dropped — current turn drives the next step.
		assertFalse(result.contains(assistant));
		assertFalse(result.contains(tool));
	}

	@Test
	void dropsHistoricalAssistantToolCallsPairWhenOlderThanLatestUser() {
		// Reproduces the "place orders in cart" → "proceed to shipping" failure: historical tool pair
		// becomes orphan after the stale-TOOL filter because the paired assistant survives.
		ConversationMessageEntity user1 = message(ConversationMessageRole.USER, "place orders in cart", null);
		ConversationMessageEntity assistant1 = message(ConversationMessageRole.ASSISTANT,
			"[tool_calls][{\"id\":\"call_nav\",\"type\":\"function\",\"function\":{\"name\":\"nav\"}}][/tool_calls]",
			null);
		ConversationMessageEntity tool1 = message(ConversationMessageRole.TOOL, "Navigated", "call_nav");
		ConversationMessageEntity user2 = message(ConversationMessageRole.USER, "proceed to shipping", null);
		ConversationMessageEntity assistant2 = message(ConversationMessageRole.ASSISTANT,
			"[tool_calls][{\"id\":\"call_click\",\"type\":\"function\",\"function\":{\"name\":\"dom_click\"}}][/tool_calls]",
			null);
		ConversationMessageEntity tool2 = message(ConversationMessageRole.TOOL, "err", "call_click");

		List<ConversationMessageEntity> result = ModelConversationWindow
			.forModel(List.of(user1, assistant1, tool1, user2, assistant2, tool2));

		assertFalse(result.contains(assistant1), "Historical assistant tool_calls must be dropped with its stale TOOL");
		assertFalse(result.contains(tool1), "Stale TOOL must be dropped");
		assertTrue(result.contains(assistant2), "Current-turn assistant tool_calls must be preserved");
		assertTrue(result.contains(tool2), "Current-turn TOOL must be preserved");
	}

	@Test
	void dropsStaleToolAndItsPairedAssistantFromOlderTurn() {
		// Historical tool pair: both assistant tool_calls and its TOOL response are dropped so the
		// model sees a clean current turn without orphaned tool_calls entries.
		ConversationMessageEntity user1 = message(ConversationMessageRole.USER, "first", null);
		ConversationMessageEntity assistant = message(ConversationMessageRole.ASSISTANT,
			"[tool_calls][{\"id\":\"call_done\",\"type\":\"function\",\"function\":{\"name\":\"x\"}}][/tool_calls]",
			null);
		ConversationMessageEntity tool = message(ConversationMessageRole.TOOL, "ok", "call_done");
		ConversationMessageEntity user2 = message(ConversationMessageRole.USER, "second", null);

		List<ConversationMessageEntity> result = ModelConversationWindow.forModel(List.of(user1, assistant, tool, user2));

		assertFalse(result.contains(assistant), "Historical assistant tool_calls should be dropped with its TOOL");
		assertFalse(result.contains(tool), "Historical TOOL should be dropped");
		assertTrue(result.contains(user1));
		assertTrue(result.contains(user2));
	}

	@Test
	void dropsMultiCallAssistantWhenAnyCallIdUnpaired() {
		ConversationMessageEntity user1 = message(ConversationMessageRole.USER, "do two things", null);
		ConversationMessageEntity assistant = message(ConversationMessageRole.ASSISTANT,
			"[tool_calls]["
				+ "{\"id\":\"call_a\",\"type\":\"function\",\"function\":{\"name\":\"x\"}},"
				+ "{\"id\":\"call_b\",\"type\":\"function\",\"function\":{\"name\":\"y\"}}"
				+ "][/tool_calls]",
			null);
		ConversationMessageEntity toolA = message(ConversationMessageRole.TOOL, "ok", "call_a");
		// call_b never got a tool response (e.g. page refresh mid-execution)
		ConversationMessageEntity user2 = message(ConversationMessageRole.USER, "keep going", null);

		List<ConversationMessageEntity> result = ModelConversationWindow.forModel(List.of(user1, assistant, toolA, user2));

		assertFalse(result.contains(assistant), "Assistant with any unpaired call_id must be dropped");
	}

	private static ConversationMessageEntity message(ConversationMessageRole role, String content, String toolCallId) {
		ConversationMessageEntity entity = new ConversationMessageEntity();
		entity.setRole(role);
		entity.setContent(content);
		entity.setToolCallId(toolCallId);
		return entity;
	}
}
