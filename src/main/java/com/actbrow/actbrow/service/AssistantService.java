package com.actbrow.actbrow.service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.actbrow.actbrow.api.NotFoundException;
import com.actbrow.actbrow.api.dto.AssistantResponse;
import com.actbrow.actbrow.api.dto.CreateAssistantRequest;
import com.actbrow.actbrow.model.AssistantDefinitionEntity;
import com.actbrow.actbrow.model.ConversationEntity;
import com.actbrow.actbrow.model.RunEntity;
import com.actbrow.actbrow.repository.ApiIntegrationRepository;
import com.actbrow.actbrow.repository.AssistantRepository;
import com.actbrow.actbrow.repository.AssistantToolBindingRepository;
import com.actbrow.actbrow.repository.ConversationMessageRepository;
import com.actbrow.actbrow.repository.ConversationRepository;
import com.actbrow.actbrow.repository.KnowledgeDocumentRepository;
import com.actbrow.actbrow.repository.McpServerRepository;
import com.actbrow.actbrow.repository.NavigationFlowRepository;
import com.actbrow.actbrow.repository.RunMemoryRepository;
import com.actbrow.actbrow.repository.RunRepository;
import com.actbrow.actbrow.repository.RunStepRepository;

@Service
public class AssistantService {

	private final AssistantRepository assistantRepository;
	private final ToolService toolService;
	private final String defaultChatModel;
	private final AssistantToolBindingRepository toolBindingRepository;
	private final NavigationFlowRepository navigationFlowRepository;
	private final KnowledgeDocumentRepository knowledgeDocumentRepository;
	private final ApiIntegrationRepository apiIntegrationRepository;
	private final McpServerRepository mcpServerRepository;
	private final ConversationRepository conversationRepository;
	private final ConversationMessageRepository conversationMessageRepository;
	private final RunRepository runRepository;
	private final RunStepRepository runStepRepository;
	private final RunMemoryRepository runMemoryRepository;

	public AssistantService(AssistantRepository assistantRepository, ToolService toolService,
		@Value("${spring.ai.openai.chat.options.model:deepseek/deepseek-v4-flash}") String defaultChatModel,
		AssistantToolBindingRepository toolBindingRepository,
		NavigationFlowRepository navigationFlowRepository,
		KnowledgeDocumentRepository knowledgeDocumentRepository,
		ApiIntegrationRepository apiIntegrationRepository,
		McpServerRepository mcpServerRepository,
		ConversationRepository conversationRepository,
		ConversationMessageRepository conversationMessageRepository,
		RunRepository runRepository,
		RunStepRepository runStepRepository,
		RunMemoryRepository runMemoryRepository) {
		this.assistantRepository = assistantRepository;
		this.toolService = toolService;
		this.defaultChatModel = defaultChatModel;
		this.toolBindingRepository = toolBindingRepository;
		this.navigationFlowRepository = navigationFlowRepository;
		this.knowledgeDocumentRepository = knowledgeDocumentRepository;
		this.apiIntegrationRepository = apiIntegrationRepository;
		this.mcpServerRepository = mcpServerRepository;
		this.conversationRepository = conversationRepository;
		this.conversationMessageRepository = conversationMessageRepository;
		this.runRepository = runRepository;
		this.runStepRepository = runStepRepository;
		this.runMemoryRepository = runMemoryRepository;
	}

	// No eviction: this always inserts a new row, and nothing can be cached under an id that did not
	// exist yet (a failed lookup throws, and exceptions are not cached). The built-in tool attach below
	// goes through the ToolService proxy, so the tool catalog is evicted by that call.
	public AssistantResponse createOrUpdate(CreateAssistantRequest request) {
		AssistantDefinitionEntity entity = new AssistantDefinitionEntity();
		entity.setKey(generateAssistantKey(request.name()));
		entity.setApiKey(generateApiKey());
		entity.setName(request.name());
		entity.setSystemPrompt(request.systemPrompt());
		entity.setModel(resolveModel(request.model(), null));
		entity.setUsePredefinedFlows(request.usePredefinedFlows());
		entity.setUserId(request.userId());
		ensureConnectKeys(entity);
		AssistantDefinitionEntity saved = assistantRepository.save(entity);
		toolService.attachBuiltInTools(saved.getId());
		return toResponse(saved);
	}

	/**
	 * Read ~3x per run plus on the auth paths, so it is cached per assistant id.
	 *
	 * <p>A missing id throws {@link NotFoundException} rather than returning null or empty. Spring's
	 * cache abstraction only stores a method's return value — a thrown exception leaves the cache
	 * untouched — so a lookup for an unknown id is never cached, and an assistant created after a
	 * failed lookup is visible immediately. There is no negative-caching hole here.
	 *
	 * <p>Note this is self-invoked by {@link #requireOwnedEntity} and {@link #update}, which bypasses
	 * the cache proxy: those two always read straight from the database. That is harmless (a redundant
	 * read, never a stale one) and is in fact what {@code update} wants — a managed, fresh entity.
	 */
	public AssistantDefinitionEntity requireEntity(String assistantId) {
		return assistantRepository.findById(assistantId)
			.orElseThrow(() -> new NotFoundException("Assistant not found"));
	}

	public AssistantDefinitionEntity requireOwnedEntity(String assistantId, String userId) {
		AssistantDefinitionEntity entity = requireEntity(assistantId);
		if (userId == null || !userId.equals(entity.getUserId())) {
			throw new NotFoundException("Assistant not found");
		}
		return entity;
	}

	public void ensureConnectKeys(AssistantDefinitionEntity entity) {
		if (entity.getSetupKey() == null || entity.getSetupKey().isBlank()) {
			entity.setSetupKey(generatePrefixedKey("sk_"));
		}
		if (entity.getWidgetKey() == null || entity.getWidgetKey().isBlank()) {
			entity.setWidgetKey(generatePrefixedKey("wk_"));
		}
	}

	/**
	 * The catch-all writer: AssistantSyncService, AssistantConnectService, ActivationStatusService and
	 * WidgetThemeService all mutate an entity and persist it through here, so this is the single point
	 * that has to evict for all of them.
	 */
	public AssistantDefinitionEntity saveEntity(AssistantDefinitionEntity entity) {
		return assistantRepository.save(entity);
	}

	public List<AssistantResponse> list() {
		return assistantRepository.findAll().stream().map(this::toResponse).toList();
	}

	public List<AssistantResponse> listByUser(String userId) {
		return assistantRepository.findAllByUserId(userId).stream()
			.map(this::toResponse)
			.toList();
	}

	public List<AssistantDefinitionEntity> listEntitiesByUser(String userId) {
		return assistantRepository.findAllByUserId(userId);
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

	/**
	 * Delete an assistant and everything that hangs off it. Idempotent: deleting an assistant that
	 * no longer exists is a no-op. Dependents are removed first because navigation_flows has a real
	 * FK to assistants; the rest is orphan cleanup so nothing dangles.
	 */
	@Transactional
	public void delete(String id) {
		if (!assistantRepository.existsById(id)) {
			return;
		}
		toolBindingRepository.deleteAllByAssistantId(id);
		navigationFlowRepository.deleteAllByAssistant_Id(id);
		knowledgeDocumentRepository.deleteAllByAssistantId(id);
		apiIntegrationRepository.deleteAllByAssistantId(id);
		mcpServerRepository.deleteByAssistantId(id);
		for (ConversationEntity conversation : conversationRepository.findAllByAssistantId(id)) {
			for (RunEntity run : runRepository.findAllByConversationId(conversation.getId())) {
				runStepRepository.deleteByRunId(run.getId());
				runMemoryRepository.deleteByRunId(run.getId());
				runRepository.delete(run);
			}
			conversationMessageRepository.deleteByConversationId(conversation.getId());
			conversationRepository.delete(conversation);
		}
		assistantRepository.deleteById(id);
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
		return generatePrefixedKey("ak_");
	}

	private String generatePrefixedKey(String prefix) {
		byte[] bytes = new byte[32];
		secureRandom.nextBytes(bytes);
		return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private String randomToken(int byteCount) {
		byte[] bytes = new byte[byteCount];
		secureRandom.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).toLowerCase(Locale.ROOT);
	}

	private final SecureRandom secureRandom = new SecureRandom();
}
