package com.actbrow.actbrow.service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

import org.springframework.stereotype.Service;

import com.actbrow.actbrow.api.dto.AssistantResponse;
import com.actbrow.actbrow.api.dto.CreateAssistantRequest;
import com.actbrow.actbrow.model.AssistantDefinitionEntity;
import com.actbrow.actbrow.repository.AssistantRepository;

@Service
public class AssistantService {

	private final AssistantRepository assistantRepository;
	private final ToolService toolService;

	public AssistantService(AssistantRepository assistantRepository, ToolService toolService) {
		this.assistantRepository = assistantRepository;
		this.toolService = toolService;
	}

	public AssistantResponse createOrUpdate(CreateAssistantRequest request) {
		AssistantDefinitionEntity entity = assistantRepository.findByKey(request.key()).orElse(null);
		boolean isNew = entity == null;
		if (isNew) {
			entity = new AssistantDefinitionEntity();
			entity.setApiKey(generateApiKey());
		}
		entity.setKey(request.key());
		entity.setName(request.name());
		entity.setSystemPrompt(request.systemPrompt());
		entity.setModel(request.model());
		entity.setUsePredefinedFlows(request.usePredefinedFlows());
		entity.setUserId(request.userId());
		AssistantDefinitionEntity saved = assistantRepository.save(entity);
		if (isNew) {
			toolService.attachBuiltInClientTools(saved.getId());
			toolService.attachHttpTools(saved.getId());
		}
		return toResponse(saved);
	}

	public AssistantDefinitionEntity requireEntity(String assistantId) {
		return assistantRepository.findById(assistantId)
			.orElseThrow(() -> new IllegalArgumentException("Assistant not found"));
	}

	public AssistantDefinitionEntity findByApiKey(String apiKey) {
		return assistantRepository.findByApiKey(apiKey)
			.orElseThrow(() -> new IllegalArgumentException("Invalid API key"));
	}

	public List<AssistantResponse> list() {
		return assistantRepository.findAll().stream().map(this::toResponse).toList();
	}

	public List<AssistantResponse> listByUser(String userId) {
		return assistantRepository.findAllByUserId(userId).stream()
			.map(this::toResponse)
			.toList();
	}

	public AssistantResponse update(String id, CreateAssistantRequest request) {
		AssistantDefinitionEntity entity = requireEntity(id);
		entity.setKey(request.key());
		entity.setName(request.name());
		entity.setSystemPrompt(request.systemPrompt());
		entity.setModel(request.model());
		entity.setUsePredefinedFlows(request.usePredefinedFlows());
		entity.setUserId(request.userId());
		AssistantDefinitionEntity saved = assistantRepository.save(entity);
		return toResponse(saved);
	}

	public AssistantResponse regenerateApiKey(String id) {
		AssistantDefinitionEntity entity = requireEntity(id);
		entity.setApiKey(generateApiKey());
		return toResponse(assistantRepository.save(entity));
	}

	private AssistantResponse toResponse(AssistantDefinitionEntity entity) {
		return new AssistantResponse(entity.getId(), entity.getKey(), entity.getName(), entity.getSystemPrompt(),
			entity.getModel(), entity.isUsePredefinedFlows(), entity.getApiKey(), entity.getUserId(),
			entity.getCreatedAt());
	}

	private String generateApiKey() {
		SecureRandom random = new SecureRandom();
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		return "ak_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}
