package com.actbrow.actbrow.service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.actbrow.actbrow.api.dto.AssistantResponse;
import com.actbrow.actbrow.api.dto.CreateAssistantRequest;
import com.actbrow.actbrow.model.AssistantDefinitionEntity;
import com.actbrow.actbrow.repository.AssistantRepository;

@Service
public class AssistantService {

	private final AssistantRepository assistantRepository;
	private final ToolService toolService;
	private final String defaultChatModel;

	public AssistantService(AssistantRepository assistantRepository, ToolService toolService,
		@Value("${spring.ai.openai.chat.options.model:deepseek-chat}") String defaultChatModel) {
		this.assistantRepository = assistantRepository;
		this.toolService = toolService;
		this.defaultChatModel = defaultChatModel;
	}

	public AssistantResponse createOrUpdate(CreateAssistantRequest request) {
		AssistantDefinitionEntity entity = new AssistantDefinitionEntity();
		entity.setKey(generateAssistantKey(request.name()));
		entity.setApiKey(generateApiKey());
		entity.setName(request.name());
		entity.setSystemPrompt(request.systemPrompt());
		entity.setModel(resolveModel(request.model(), null));
		entity.setUsePredefinedFlows(request.usePredefinedFlows());
		entity.setUserId(request.userId());
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

	public List<AssistantResponse> listByUser(String userId) {
		return assistantRepository.findAllByUserId(userId).stream()
			.map(this::toResponse)
			.toList();
	}

	public AssistantResponse update(String id, CreateAssistantRequest request) {
		AssistantDefinitionEntity entity = requireEntity(id);
		entity.setName(request.name());
		entity.setSystemPrompt(request.systemPrompt());
		entity.setModel(resolveModel(request.model(), entity));
		entity.setUsePredefinedFlows(request.usePredefinedFlows());
		entity.setUserId(request.userId());
		AssistantDefinitionEntity saved = assistantRepository.save(entity);
		return toResponse(saved);
	}

	private String resolveModel(String requested, AssistantDefinitionEntity existingForUpdate) {
		if (requested != null && !requested.isBlank()) {
			return requested.trim();
		}
		if (existingForUpdate != null) {
			String kept = existingForUpdate.getModel();
			if (kept != null && !kept.isBlank()) {
				return kept;
			}
		}
		return defaultChatModel;
	}

	private AssistantResponse toResponse(AssistantDefinitionEntity entity) {
		return new AssistantResponse(entity.getId(), entity.getKey(), entity.getName(), entity.getSystemPrompt(),
			entity.getModel(), entity.isUsePredefinedFlows(), entity.getUserId(),
			entity.getCreatedAt());
	}

	private String generateAssistantKey(String name) {
		String base = name == null ? "" : name.toLowerCase(Locale.ROOT)
			.replaceAll("[^a-z0-9]+", "-")
			.replaceAll("(^-|-$)", "");
		if (base.isBlank()) {
			base = "assistant";
		}
		String key;
		do {
			key = base + "-" + randomToken(6);
		} while (assistantRepository.findByKey(key).isPresent());
		return key;
	}

	private String generateApiKey() {
		byte[] bytes = new byte[32];
		secureRandom.nextBytes(bytes);
		return "ak_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private String randomToken(int byteCount) {
		byte[] bytes = new byte[byteCount];
		secureRandom.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).toLowerCase(Locale.ROOT);
	}

	private final SecureRandom secureRandom = new SecureRandom();
}
