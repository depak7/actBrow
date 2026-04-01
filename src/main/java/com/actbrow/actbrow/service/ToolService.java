package com.actbrow.actbrow.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.actbrow.actbrow.agent.ToolDescriptor;
import com.actbrow.actbrow.api.dto.ToolRequest;
import com.actbrow.actbrow.api.dto.ToolResponse;
import com.actbrow.actbrow.model.AssistantToolBindingEntity;
import com.actbrow.actbrow.model.ToolDefinitionEntity;
import com.actbrow.actbrow.repository.AssistantToolBindingRepository;
import com.actbrow.actbrow.repository.ToolRepository;

@Service
public class ToolService {

	private final ToolRepository toolRepository;
	private final AssistantToolBindingRepository bindingRepository;
	private final JsonSchemaValidator jsonSchemaValidator;

	public ToolService(ToolRepository toolRepository, AssistantToolBindingRepository bindingRepository,
		JsonSchemaValidator jsonSchemaValidator) {
		this.toolRepository = toolRepository;
		this.bindingRepository = bindingRepository;
		this.jsonSchemaValidator = jsonSchemaValidator;
	}

	public ToolResponse create(ToolRequest request) {
		toolRepository.findByKey(request.key()).ifPresent(existing -> {
			throw new IllegalArgumentException("Tool key already exists");
		});
		return saveNewEntity(new ToolDefinitionEntity(), request);
	}

	public ToolResponse upsertByKey(ToolRequest request) {
		return toolRepository.findByKey(request.key())
			.map(existing -> saveNewEntity(existing, request))
			.orElseGet(() -> saveNewEntity(new ToolDefinitionEntity(), request));
	}

	public void attachBuiltInClientTools(String assistantId) {
		for (ToolResponse tool : list()) {
			if (tool.type().name().equals("CLIENT")
				&& tool.executorRef() != null
				&& tool.key().equals(tool.executorRef())) {
				bindingRepository.findByAssistantIdAndToolId(assistantId, tool.id()).ifPresentOrElse(
					existing -> {
					},
					() -> {
						AssistantToolBindingEntity entity = new AssistantToolBindingEntity();
						entity.setAssistantId(assistantId);
						entity.setToolId(tool.id());
						bindingRepository.save(entity);
					});
			}
		}
	}

	public ToolResponse update(String id, ToolRequest request) {
		ToolDefinitionEntity entity = requireEntity(id);
		if (!entity.getKey().equals(request.key())) {
			toolRepository.findByKey(request.key()).ifPresent(existing -> {
				throw new IllegalArgumentException("Tool key already exists");
			});
		}
		return saveNewEntity(entity, request);
	}

	private ToolResponse saveNewEntity(ToolDefinitionEntity entity, ToolRequest request) {
		String inputSchema = jsonSchemaValidator.normalizeObject(request.inputSchema(), "inputSchema");
		String outputSchema = request.outputSchema() == null ? null
			: jsonSchemaValidator.normalizeObject(request.outputSchema(), "outputSchema");
		String defaultArguments = request.defaultArguments() == null ? null
			: jsonSchemaValidator.normalizeObject(request.defaultArguments(), "defaultArguments");
		entity.setKey(request.key());
		entity.setDisplayName(request.displayName());
		entity.setDescription(request.description());
		entity.setInputSchema(inputSchema);
		entity.setOutputSchema(outputSchema);
		entity.setType(request.type());
		entity.setVersion(request.version());
		entity.setEnabled(request.enabled());
		entity.setExecutorRef(request.executorRef());
		entity.setDefaultArguments(defaultArguments);
		return toResponse(toolRepository.save(entity));
	}

	public List<ToolResponse> list() {
		return toolRepository.findAll().stream().map(this::toResponse).toList();
	}

	public List<ToolResponse> listAssistantTools(String assistantId) {
		return loadAssistantTools(assistantId).stream().map(this::toResponse).toList();
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

	public ToolResponse createAndAttach(String assistantId, ToolRequest request) {
		ToolResponse tool = create(request);
		attachTool(assistantId, tool.id());
		return tool;
	}

	public void detachTool(String assistantId, String toolId) {
		AssistantToolBindingEntity binding = bindingRepository.findByAssistantIdAndToolId(assistantId, toolId)
			.orElseThrow(() -> new IllegalArgumentException("Assistant tool binding not found"));
		bindingRepository.delete(binding);
	}

	public ToolDefinitionEntity requireEntity(String toolId) {
		return toolRepository.findById(toolId)
			.orElseThrow(() -> new IllegalArgumentException("Tool not found"));
	}

	public List<ToolDescriptor> listDescriptorsForAssistant(String assistantId) {
		return loadAssistantTools(assistantId).stream()
			.filter(ToolDefinitionEntity::isEnabled)
			.map(tool -> new ToolDescriptor(tool.getId(), tool.getKey(), tool.getDescription(), tool.getInputSchema(),
				tool.getType(), tool.getExecutorRef(),
				tool.getDefaultArguments() == null ? Map.of() : jsonSchemaValidator.parseObject(tool.getDefaultArguments())))
			.toList();
	}

	private List<ToolDefinitionEntity> loadAssistantTools(String assistantId) {
		Set<String> toolIds = new LinkedHashSet<>();
		for (AssistantToolBindingEntity binding : bindingRepository.findAllByAssistantId(assistantId)) {
			toolIds.add(binding.getToolId());
		}
		return toolIds.stream()
			.map(this::requireEntity)
			.toList();
	}

	private ToolResponse toResponse(ToolDefinitionEntity entity) {
		return new ToolResponse(entity.getId(), entity.getKey(), entity.getDisplayName(), entity.getDescription(),
			jsonSchemaValidator.parseObject(entity.getInputSchema()),
			entity.getOutputSchema() == null ? null : jsonSchemaValidator.parseObject(entity.getOutputSchema()),
			entity.getType(), entity.getVersion(),
			entity.isEnabled(), entity.getExecutorRef(),
			entity.getDefaultArguments() == null ? null : jsonSchemaValidator.parseObject(entity.getDefaultArguments()),
			entity.getCreatedAt());
	}
}
