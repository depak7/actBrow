package com.actbrow.actbrow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.actbrow.actbrow.agent.ToolDescriptor;
import com.actbrow.actbrow.model.AssistantToolBindingEntity;
import com.actbrow.actbrow.model.ToolDefinitionEntity;
import com.actbrow.actbrow.model.ToolType;
import com.actbrow.actbrow.repository.AssistantRepository;
import com.actbrow.actbrow.repository.AssistantToolBindingRepository;
import com.actbrow.actbrow.repository.ToolRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Guards the tool-catalog load against regressing to a per-id fetch loop. This runs once per run and
 * again on every progressive-disclosure meta-tool call, so an N+1 here scales the whole run's query
 * count with the number of attached tools.
 */
class ToolServiceCatalogTests {

	@Mock
	private ToolRepository toolRepository;

	@Mock
	private AssistantToolBindingRepository bindingRepository;

	@Mock
	private AssistantRepository assistantRepository;

	private ToolService toolService;

	@BeforeEach
	void setUp() {
		MockitoAnnotations.openMocks(this);
		toolService = new ToolService(toolRepository, bindingRepository, assistantRepository,
			new JsonSchemaValidator(new ObjectMapper()));
	}

	private AssistantToolBindingEntity binding(String toolId) {
		AssistantToolBindingEntity entity = new AssistantToolBindingEntity();
		entity.setAssistantId("assistant-1");
		entity.setToolId(toolId);
		return entity;
	}

	private ToolDefinitionEntity tool(String id, String key) {
		ToolDefinitionEntity entity = new ToolDefinitionEntity();
		entity.setId(id);
		entity.setKey(key);
		entity.setDisplayName(key);
		entity.setDescription("desc " + key);
		entity.setInputSchema("{\"type\":\"object\"}");
		entity.setType(ToolType.SERVER_HTTP);
		entity.setVersion("1");
		entity.setEnabled(true);
		return entity;
	}

	@Test
	@SuppressWarnings("unchecked")
	void loadsWholeCatalogInASingleBatchQuery() {
		when(bindingRepository.findAllByAssistantId("assistant-1"))
			.thenReturn(List.of(binding("t1"), binding("t2"), binding("t3")));
		when(toolRepository.findAllById(any()))
			.thenReturn(List.of(tool("t1", "a.one"), tool("t2", "a.two"), tool("t3", "a.three")));

		List<ToolDescriptor> descriptors = toolService.listDescriptorsForAssistant("assistant-1");

		assertThat(descriptors).hasSize(3);
		// One batch fetch, and crucially never a per-id lookup.
		verify(toolRepository, times(1)).findAllById(any());
		verify(toolRepository, never()).findById(any());

		ArgumentCaptor<Iterable<String>> ids = ArgumentCaptor.forClass(Iterable.class);
		verify(toolRepository).findAllById(ids.capture());
		assertThat(ids.getValue()).containsExactly("t1", "t2", "t3");
	}

	@Test
	void preservesBindingOrderRegardlessOfBatchResultOrder() {
		when(bindingRepository.findAllByAssistantId("assistant-1"))
			.thenReturn(List.of(binding("t1"), binding("t2"), binding("t3")));
		// findAllById makes no ordering guarantee — return them shuffled.
		when(toolRepository.findAllById(any()))
			.thenReturn(List.of(tool("t3", "a.three"), tool("t1", "a.one"), tool("t2", "a.two")));

		List<ToolDescriptor> descriptors = toolService.listDescriptorsForAssistant("assistant-1");

		assertThat(descriptors).extracting(ToolDescriptor::key)
			.containsExactly("a.one", "a.two", "a.three");
	}

	@Test
	void skipsBindingsWhoseToolNoLongerExists() {
		when(bindingRepository.findAllByAssistantId("assistant-1"))
			.thenReturn(List.of(binding("t1"), binding("missing"), binding("t2")));
		when(toolRepository.findAllById(any()))
			.thenReturn(List.of(tool("t1", "a.one"), tool("t2", "a.two")));

		List<ToolDescriptor> descriptors = toolService.listDescriptorsForAssistant("assistant-1");

		// A single stale binding must not take down every run for the assistant.
		assertThat(descriptors).extracting(ToolDescriptor::key).containsExactly("a.one", "a.two");
	}

	@Test
	void emptyBindingsIssueNoToolQueryAtAll() {
		when(bindingRepository.findAllByAssistantId("assistant-1")).thenReturn(List.of());

		assertThat(toolService.listDescriptorsForAssistant("assistant-1")).isEmpty();

		verify(toolRepository, never()).findAllById(any());
		verify(toolRepository, never()).findById(any());
	}
}
