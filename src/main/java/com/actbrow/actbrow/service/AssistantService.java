package com.actbrow.actbrow.service;

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
		if(entity == null){
			entity = new AssistantDefinitionEntity();
		}
		entity.setKey(request.key());
		entity.setName(request.name());
		entity.setSystemPrompt(request.systemPrompt());
		entity.setModel(request.model());
		entity.setUsePredefinedFlows(request.usePredefinedFlows());
		entity.setTenantId(request.tenantId());
		AssistantDefinitionEntity saved = assistantRepository.save(entity);
		toolService.attachBuiltInClientTools(saved.getId());
		return toResponse(saved);
	}

	public AssistantDefinitionEntity requireEntity(String assistantId) {
		return assistantRepository.findById(assistantId)
			.orElseThrow(() -> new IllegalArgumentException("Assistant not found"));
	}

	public List<AssistantResponse> list() {
		return assistantRepository.findAll().stream().map(this::toResponse).toList();
	}

	public List<AssistantResponse> listByTenant(String tenantId) {
		return assistantRepository.findAllByTenantId(tenantId).stream()
			.map(this::toResponse)
			.toList();
	}

	private AssistantResponse toResponse(AssistantDefinitionEntity entity) {
		return new AssistantResponse(entity.getId(), entity.getKey(), entity.getName(), entity.getSystemPrompt(),
			entity.getModel(), entity.isUsePredefinedFlows(), entity.getTenantId(), entity.getCreatedAt());
	}
}
