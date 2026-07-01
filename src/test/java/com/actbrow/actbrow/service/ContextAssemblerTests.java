package com.actbrow.actbrow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.actbrow.actbrow.model.AssistantDefinitionEntity;
import com.actbrow.actbrow.model.ConversationMessageEntity;
import com.actbrow.actbrow.model.ConversationMessageRole;
import com.actbrow.actbrow.model.RunEntity;

class ContextAssemblerTests {

	@Test
	void assembleBuildsLayeredPromptWithMemoryAndRecentState() {
		RunMemoryService runMemoryService = mock(RunMemoryService.class);
		when(runMemoryService.getSnapshot("run-1")).thenReturn(new RunMemoryService.RunMemorySnapshot(
			"Find the customer order",
			"Recover from the latest tool failure",
			"Return the order status",
			Map.of("orderId", "ord_123", "path", "/orders"),
			Map.of("kind", "tool_result", "toolKey", "orders.fetch"),
			List.of(Map.of("toolKey", "orders.fetch", "error", "HTTP 500")),
			"HTTP 500",
			Map.of("status", "blocked")));

		ContextAssembler assembler = new ContextAssembler(runMemoryService);

		RunEntity run = new RunEntity();
		run.setId("run-1");
		run.setConversationId("conv-1");

		AssistantDefinitionEntity assistant = new AssistantDefinitionEntity();
		assistant.setId("asst-1");
		assistant.setName("Support");

		ConversationMessageEntity user = new ConversationMessageEntity();
		user.setConversationId("conv-1");
		user.setRole(ConversationMessageRole.USER);
		user.setContent("Check order"
			+ com.actbrow.actbrow.conversation.UserMessageDisplay.PAGE_CONTEXT_APPENDIX_START
			+ "Observation only — describes where the user currently is. Do not act on it directly; use the attached tools.) ---\n"
			+ "{\"path\":\"/orders\",\"title\":\"Orders\"}");

		ConversationMessageEntity tool = new ConversationMessageEntity();
		tool.setConversationId("conv-1");
		tool.setRole(ConversationMessageRole.TOOL);
		tool.setContent("{\"status\":\"failed\"}");
		tool.setId("msg-2");

		List<ConversationMessageEntity> messages = List.of(user, tool);

		ContextAssembler.ContextAssembly assembly = assembler.assemble(assistant, run, messages,
			"BASE PROMPT\n", "RUNTIME RETRY STATE FOR THIS RUN:\n");

		assertThat(assembly.systemPrompt()).contains("WORKING MEMORY:");
		assertThat(assembly.systemPrompt()).contains("CURRENT STATE:");
		assertThat(assembly.systemPrompt()).contains("RECENT HISTORY:");
		assertThat(assembly.systemPrompt()).contains("orderId=ord_123");
		assertThat(assembly.systemPrompt()).contains("/orders");
		assertThat(assembly.systemPrompt()).contains("RUNTIME RETRY STATE FOR THIS RUN");
	}
}
