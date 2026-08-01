package com.actbrow.actbrow.service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.actbrow.actbrow.agent.ToolDescriptor;
import com.actbrow.actbrow.api.NotFoundException;
import com.actbrow.actbrow.api.dto.ToolRequest;
import com.actbrow.actbrow.api.dto.ToolResponse;
import com.actbrow.actbrow.model.AssistantDefinitionEntity;
import com.actbrow.actbrow.model.AssistantToolBindingEntity;
import com.actbrow.actbrow.model.ToolDefinitionEntity;
import com.actbrow.actbrow.model.ToolType;
import com.actbrow.actbrow.repository.AssistantRepository;
import com.actbrow.actbrow.repository.AssistantToolBindingRepository;
import com.actbrow.actbrow.repository.ToolRepository;

@Service
public class ToolService {

	private final ToolRepository toolRepository;
	private final AssistantToolBindingRepository bindingRepository;
	private final AssistantRepository assistantRepository;
	private final JsonSchemaValidator jsonSchemaValidator;

	public ToolService(ToolRepository toolRepository, AssistantToolBindingRepository bindingRepository,
		AssistantRepository assistantRepository, JsonSchemaValidator jsonSchemaValidator) {
		this.toolRepository = toolRepository;
		this.bindingRepository = bindingRepository;
		this.assistantRepository = assistantRepository;
		this.jsonSchemaValidator = jsonSchemaValidator;
	}

	public ToolResponse create(ToolRequest request) {
		if (request.type() == ToolType.BUILD_IN) {
			throw new IllegalArgumentException("BUILD_IN tools are platform-managed");
		}
		String key = resolveToolKey(request.key());
		toolRepository.findByKey(key).ifPresent(existing -> {
			throw new IllegalArgumentException("Tool key already exists");
		});
		return saveNewEntity(new ToolDefinitionEntity(), request, key);
	}

	public ToolResponse upsertByKey(ToolRequest request) {
		if (request.key() == null || request.key().isBlank()) {
			throw new IllegalArgumentException("Tool key is required for upsert");
		}
		String key = request.key().trim();
		return toolRepository.findByKey(key)
			.map(existing -> saveNewEntity(existing, request, key))
			.orElseGet(() -> saveNewEntity(new ToolDefinitionEntity(), request, key));
	}

	public void attachBuiltInTools(String assistantId) {
		for (ToolDefinitionEntity toolEntity : toolRepository.findAll()) {
			ToolResponse tool = toResponse(toolEntity);
			if (!ToolCatalogPolicies.isBuiltInAttachmentCandidate(tool)) {
				continue;
			}
			bindingRepository.findByAssistantIdAndToolId(assistantId, tool.id()).ifPresentOrElse(
				existing -> {
				},
				() -> {
					AssistantToolBindingEntity binding = new AssistantToolBindingEntity();
					binding.setAssistantId(assistantId);
					binding.setToolId(tool.id());
					bindingRepository.save(binding);
				});
		}
	}

	public void attachBuiltInClientTools(String assistantId) {
		attachBuiltInTools(assistantId);
	}

	@Transactional
	public void deleteByKeyIfPresent(String key) {
		toolRepository.findByKey(key).ifPresent(entity -> delete(entity.getId()));
	}

	public ToolResponse update(String id, ToolRequest request) {
		ToolDefinitionEntity entity = requireEntity(id);
		String newKey = request.key() == null || request.key().isBlank() ? entity.getKey() : request.key().trim();
		if (!entity.getKey().equals(newKey)) {
			toolRepository.findByKey(newKey).ifPresent(existing -> {
				throw new IllegalArgumentException("Tool key already exists");
			});
		}
		return saveNewEntity(entity, request, newKey);
	}

	@Transactional
	public void delete(String toolId) {
		requireEntity(toolId);
		bindingRepository.deleteAll(bindingRepository.findAllByToolId(toolId));
		toolRepository.deleteById(toolId);
	}

	private String resolveToolKey(String requested) {
		if (requested != null && !requested.isBlank()) {
			return requested.trim();
		}
		String candidate;
		for (int attempt = 0; attempt < 32; attempt++) {
			candidate = "tool_" + UUID.randomUUID().toString().replace("-", "");
			if (toolRepository.findByKey(candidate).isEmpty()) {
				return candidate;
			}
		}
		throw new IllegalStateException("Could not allocate a unique tool key");
	}

	private ToolResponse saveNewEntity(ToolDefinitionEntity entity, ToolRequest request, String keyForEntity) {
		String inputSchema = jsonSchemaValidator.normalizeObject(request.inputSchema(), "inputSchema");
		String outputSchema = request.outputSchema() == null ? null
			: jsonSchemaValidator.normalizeObject(request.outputSchema(), "outputSchema");
		String defaultArguments = request.defaultArguments() == null ? null
			: jsonSchemaValidator.normalizeObject(request.defaultArguments(), "defaultArguments");
		String metadata = request.metadata() == null ? null
			: jsonSchemaValidator.normalizeObject(request.metadata(), "metadata");
		entity.setKey(keyForEntity);
		entity.setDisplayName(request.displayName());
		entity.setDescription(request.description());
		entity.setInputSchema(inputSchema);
		entity.setOutputSchema(outputSchema);
		entity.setType(request.type());
		entity.setVersion(request.version());
		entity.setEnabled(request.enabled());
		entity.setExecutorRef(request.executorRef());
		entity.setDefaultArguments(defaultArguments);
		entity.setMetadata(metadata);
		return toResponse(toolRepository.save(entity));
	}

	public List<ToolResponse> list() {
		return toolRepository.findAll().stream()
			.filter(entity -> !ToolCatalogPolicies.isPlatformCatalogTool(entity.getType(), entity.getKey(),
				entity.getExecutorRef()))
			.map(this::toResponse)
			.toList();
	}

	/** Tools attached to any assistant owned by the user (plus unbound non-platform tools they created via attach flows). */
	public List<ToolResponse> listForUser(String userId) {
		Set<String> ownedAssistantIds = assistantRepository.findAllByUserId(userId).stream()
			.map(AssistantDefinitionEntity::getId)
			.collect(Collectors.toSet());
		Set<String> toolIds = new LinkedHashSet<>();
		for (String assistantId : ownedAssistantIds) {
			for (AssistantToolBindingEntity binding : bindingRepository.findAllByAssistantId(assistantId)) {
				toolIds.add(binding.getToolId());
			}
		}
		return toolIds.stream()
			.map(this::requireEntity)
			.filter(entity -> !ToolCatalogPolicies.isPlatformCatalogTool(entity.getType(), entity.getKey(),
				entity.getExecutorRef()))
			.map(this::toResponse)
			.toList();
	}

	/**
	 * Ensures the tool is only mutated if it is attached solely to the caller's assistants,
	 * or not attached to any assistant yet.
	 */
	public ToolDefinitionEntity requireOwnedOrUnboundTool(String toolId, String userId) {
		ToolDefinitionEntity tool = requireEntity(toolId);
		List<AssistantToolBindingEntity> bindings = bindingRepository.findAllByToolId(toolId);
		if (bindings.isEmpty()) {
			return tool;
		}
		Set<String> ownedAssistantIds = assistantRepository.findAllByUserId(userId).stream()
			.map(AssistantDefinitionEntity::getId)
			.collect(Collectors.toSet());
		boolean foreign = bindings.stream()
			.map(AssistantToolBindingEntity::getAssistantId)
			.anyMatch(assistantId -> !ownedAssistantIds.contains(assistantId));
		if (foreign) {
			throw new NotFoundException("Tool not found");
		}
		return tool;
	}

	public List<ToolResponse> listAssistantTools(String assistantId) {
		return loadAssistantTools(assistantId).stream()
			.filter(entity -> !ToolCatalogPolicies.isHiddenFromAssistantManagementList(entity))
			.map(this::toResponse)
			.toList();
	}

	public void attachTool(String assistantId, String toolId) {
		requireEntity(toolId);
		bindingRepository.findByAssistantIdAndToolId(assistantId, toolId).ifPresent(binding -> {
			throw new IllegalArgumentException("Tool already attached to assistant");
		});
		AssistantToolBindingEntity entity = new AssistantToolBindingEntity();
		entity.setAssistantId(assistantId);
		entity.setToolId(toolId);
		bindingRepository.save(entity);
	}

	public Optional<ToolDefinitionEntity> findByKey(String key) {
		return toolRepository.findByKey(key);
	}

	public void attachToolIfAbsent(String assistantId, String toolKey) {
		ToolDefinitionEntity tool = toolRepository.findByKey(toolKey)
			.orElseThrow(() -> new NotFoundException("Tool not found: " + toolKey));
		if (bindingRepository.findByAssistantIdAndToolId(assistantId, tool.getId()).isPresent()) {
			return;
		}
		attachTool(assistantId, tool.getId());
	}

	public ToolResponse createAndAttach(String assistantId, ToolRequest request) {
		ToolResponse tool = create(request);
		attachTool(assistantId, tool.id());
		return tool;
	}

	public void detachTool(String assistantId, String toolId) {
		AssistantToolBindingEntity binding = bindingRepository.findByAssistantIdAndToolId(assistantId, toolId)
			.orElseThrow(() -> new NotFoundException("Assistant tool binding not found"));
		bindingRepository.delete(binding);
	}

	public ToolDefinitionEntity requireEntity(String toolId) {
		return toolRepository.findById(toolId)
			.orElseThrow(() -> new NotFoundException("Tool not found"));
	}

	public List<ToolDescriptor> listDescriptorsForAssistant(String assistantId) {
		return loadAssistantTools(assistantId).stream()
			.filter(ToolDefinitionEntity::isEnabled)
			.map(tool -> new ToolDescriptor(tool.getId(), tool.getKey(), tool.getDescription(), tool.getInputSchema(),
				tool.getType(), tool.getExecutorRef(),
				tool.getDefaultArguments() == null ? Map.of() : jsonSchemaValidator.parseObject(tool.getDefaultArguments()),
				tool.getMetadata() == null ? Map.of() : jsonSchemaValidator.parseObject(tool.getMetadata())))
			.toList();
	}

	/**
	 * Loads an assistant's tools in two queries rather than {@code 1 + N}. This runs once per run and
	 * again on every progressive-disclosure meta-tool call, so a per-id fetch loop made the catalog
	 * cost scale with the number of attached tools.
	 *
	 * <p>{@code findAllById} does not preserve the requested order, so results are re-sorted back into
	 * binding order — the catalog order determines which tools get seeded into the model's context.
	 * A binding pointing at a deleted tool is skipped rather than throwing: one stale row should not
	 * take down every run for the assistant.
	 */
	private List<ToolDefinitionEntity> loadAssistantTools(String assistantId) {
		Set<String> toolIds = new LinkedHashSet<>();
		for (AssistantToolBindingEntity binding : bindingRepository.findAllByAssistantId(assistantId)) {
			toolIds.add(binding.getToolId());
		}
		if (toolIds.isEmpty()) {
			return List.of();
		}
		Map<String, ToolDefinitionEntity> byId = new LinkedHashMap<>();
		for (ToolDefinitionEntity tool : toolRepository.findAllById(toolIds)) {
			byId.put(tool.getId(), tool);
		}
		return toolIds.stream()
			.map(byId::get)
			.filter(java.util.Objects::nonNull)
			.toList();
	}

	private ToolResponse toResponse(ToolDefinitionEntity entity) {
		return new ToolResponse(entity.getId(), entity.getKey(), entity.getDisplayName(), entity.getDescription(),
			jsonSchemaValidator.parseObject(entity.getInputSchema()),
			entity.getOutputSchema() == null ? null : jsonSchemaValidator.parseObject(entity.getOutputSchema()),
			entity.getType(), entity.getVersion(),
			entity.isEnabled(), entity.getExecutorRef(),
			entity.getDefaultArguments() == null ? null : jsonSchemaValidator.parseObject(entity.getDefaultArguments()),
			entity.getMetadata() == null ? Map.of() : jsonSchemaValidator.parseObject(entity.getMetadata()),
			entity.getCreatedAt());
	}
}
