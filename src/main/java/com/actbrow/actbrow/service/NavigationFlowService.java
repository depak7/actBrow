package com.actbrow.actbrow.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.actbrow.actbrow.api.dto.NavigationFlowRequest;
import com.actbrow.actbrow.api.dto.NavigationFlowResponse;
import com.actbrow.actbrow.model.AssistantDefinitionEntity;
import com.actbrow.actbrow.model.NavigationFlowEntity;
import com.actbrow.actbrow.repository.NavigationFlowRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class NavigationFlowService {

	private final NavigationFlowRepository navigationFlowRepository;
	private final ObjectMapper objectMapper;

	public NavigationFlowService(NavigationFlowRepository navigationFlowRepository, ObjectMapper objectMapper) {
		this.navigationFlowRepository = navigationFlowRepository;
		this.objectMapper = objectMapper;
	}

	public List<NavigationFlowResponse> listByAssistant(String assistantId) {
		return navigationFlowRepository.findAllByAssistantIdOrderByCreatedAt(assistantId)
			.stream()
			.map(this::toResponse)
			.toList();
	}

	public List<NavigationFlowResponse> listEnabledFlows(String assistantId) {
		return navigationFlowRepository.findAllByAssistantIdAndEnabledTrueOrderByCreatedAt(assistantId)
			.stream()
			.map(this::toResponse)
			.toList();
	}

	public NavigationFlowResponse create(String assistantId, NavigationFlowRequest request,
		AssistantDefinitionEntity assistant) {
		NavigationFlowEntity entity = new NavigationFlowEntity();
		entity.setAssistant(assistant);
		entity.setName(request.name());
		entity.setTriggerPhrase(request.triggerPhrase());
		entity.setStepsJson(toJson(request.steps()));
		entity.setEnabled(request.enabled());
		return toResponse(navigationFlowRepository.save(entity));
	}

	public NavigationFlowResponse update(String assistantId, String flowId, NavigationFlowRequest request,
		AssistantDefinitionEntity assistant) {
		NavigationFlowEntity entity = navigationFlowRepository
			.findByAssistantIdAndId(assistantId, flowId)
			.orElseThrow(() -> new IllegalArgumentException("Navigation flow not found"));
		entity.setName(request.name());
		entity.setTriggerPhrase(request.triggerPhrase());
		entity.setStepsJson(toJson(request.steps()));
		entity.setEnabled(request.enabled());
		return toResponse(navigationFlowRepository.save(entity));
	}

	public void delete(String assistantId, String flowId) {
		NavigationFlowEntity entity = navigationFlowRepository
			.findByAssistantIdAndId(assistantId, flowId)
			.orElseThrow(() -> new IllegalArgumentException("Navigation flow not found"));
		navigationFlowRepository.delete(entity);
	}

	public NavigationFlowEntity findById(String assistantId, String flowId) {
		return navigationFlowRepository.findByAssistantIdAndId(assistantId, flowId)
			.orElseThrow(() -> new IllegalArgumentException("Navigation flow not found"));
	}

	private NavigationFlowResponse toResponse(NavigationFlowEntity entity) {
		return new NavigationFlowResponse(
			entity.getId(),
			entity.getAssistant().getId(),
			entity.getName(),
			entity.getTriggerPhrase(),
			parseSteps(entity.getStepsJson()),
			entity.isEnabled(),
			entity.getCreatedAt());
	}

	private List<NavigationFlowResponse.NavigationStep> parseSteps(String stepsJson) {
		try {
			return objectMapper.readValue	(stepsJson,
				objectMapper.getTypeFactory().constructCollectionType(List.class,
					NavigationFlowResponse.NavigationStep.class));
		}
		catch (JsonProcessingException e) {
			throw new IllegalArgumentException("Invalid steps JSON", e);
		}
	}

	private String toJson(List<NavigationFlowRequest.NavigationStep> steps) {
		try {
			return objectMapper.writeValueAsString(steps.stream()
				.map(step -> new NavigationFlowResponse.NavigationStep(step.action(), step.target(),
					step.description()))
				.toList());
		}
		catch (JsonProcessingException e) {
			throw new IllegalArgumentException("Cannot serialize steps", e);
		}
	}
}
