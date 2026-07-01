package com.actbrow.actbrow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.actbrow.actbrow.agent.ToolCall;
import com.actbrow.actbrow.agent.ToolDescriptor;
import com.actbrow.actbrow.agent.ToolExecutionResult;
import com.actbrow.actbrow.model.RunEntity;
import com.actbrow.actbrow.model.ToolType;
import com.actbrow.actbrow.repository.RunMemoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

class RunMemoryServiceTests {

	@Mock
	private RunMemoryRepository runMemoryRepository;

	private RunMemoryService runMemoryService;
	private ObjectMapper objectMapper;

	@BeforeEach
	void setUp() {
		MockitoAnnotations.openMocks(this);
		objectMapper = new ObjectMapper().findAndRegisterModules();
		runMemoryService = new RunMemoryService(runMemoryRepository, objectMapper);
		when(runMemoryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
	}

	@Test
	void initializeForRunStoresObjectiveAndInitialPath() {
		RunEntity run = new RunEntity();
		run.setId("run-1");
		run.setConversationId("conv-1");

		String userContent = "Open billing"
			+ com.actbrow.actbrow.conversation.UserMessageDisplay.PAGE_CONTEXT_APPENDIX_START
			+ "Observation only — describes where the user currently is. Do not act on it directly; use the attached tools.) ---\n"
			+ "{\"path\":\"/settings/billing\",\"title\":\"Billing\"}";

		runMemoryService.initializeForRun(run, userContent);

		ArgumentCaptor<com.actbrow.actbrow.model.RunMemoryEntity> captor =
			ArgumentCaptor.forClass(com.actbrow.actbrow.model.RunMemoryEntity.class);
		verify(runMemoryRepository).save(captor.capture());

		var saved = captor.getValue();
		assertThat(saved.getRunId()).isEqualTo("run-1");
		assertThat(saved.getObjective()).isEqualTo("Open billing");
		assertThat(saved.getKnownEntitiesJson()).contains("/settings/billing");
	}

	@Test
	void recordToolResultTracksFailuresAndKnownEntities() {
		RunEntity run = new RunEntity();
		run.setId("run-2");
		run.setConversationId("conv-2");

		var existing = new com.actbrow.actbrow.model.RunMemoryEntity();
		existing.setRunId("run-2");
		existing.setConversationId("conv-2");
		existing.setObjective("Update order");
		existing.setCurrentStepGoal("Continue");
		existing.setSuccessCriteria("Finish");
		existing.setKnownEntitiesJson("{}");
		existing.setLastActionJson("{}");
		existing.setLastFailuresJson("[]");
		existing.setSummaryJson("{}");
		when(runMemoryRepository.findByRunId("run-2")).thenReturn(Optional.of(existing));

		Map<String, Object> arguments = new LinkedHashMap<>();
		arguments.put("orderId", "ord_123");
		ToolCall toolCall = new ToolCall("tc-1", "tool-1", "orders.update", arguments);
		ToolDescriptor tool = new ToolDescriptor("tool-1", "orders.update", "Update order", "{}",
			ToolType.SERVER_HTTP, "orders.update", Map.of(), Map.of());
		ToolExecutionResult result = new ToolExecutionResult(false,
			"{\"status\":\"failed\",\"orderId\":\"ord_123\"}",
			"Upstream rejected the update",
			"HTTP 409 conflict");

		runMemoryService.recordToolResult(run, toolCall, tool, arguments, result, 1);

		ArgumentCaptor<com.actbrow.actbrow.model.RunMemoryEntity> captor =
			ArgumentCaptor.forClass(com.actbrow.actbrow.model.RunMemoryEntity.class);
		verify(runMemoryRepository).save(captor.capture());

		var saved = captor.getValue();
		assertThat(saved.getBlockedReason()).contains("409");
		assertThat(saved.getKnownEntitiesJson()).contains("ord_123");
		assertThat(saved.getLastFailuresJson()).contains("orders.update");
		assertThat(saved.getCurrentStepGoal()).contains("Recover");
	}
}
