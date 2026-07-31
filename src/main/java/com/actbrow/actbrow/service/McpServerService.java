package com.actbrow.actbrow.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.actbrow.actbrow.api.NotFoundException;
import com.actbrow.actbrow.api.dto.McpServerRequest;
import com.actbrow.actbrow.api.dto.McpServerResponse;
import com.actbrow.actbrow.api.dto.McpSyncResponse;
import com.actbrow.actbrow.api.dto.ToolRequest;
import com.actbrow.actbrow.model.McpServerEntity;
import com.actbrow.actbrow.model.ToolType;
import com.actbrow.actbrow.repository.McpServerRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class McpServerService {

	private final McpServerRepository mcpServerRepository;
	private final McpHttpClient mcpHttpClient;
	private final ToolService toolService;
	private final ObjectMapper objectMapper;
	private final OutboundUrlPolicy outboundUrlPolicy;
	private final SecretCryptoService secretCryptoService;

	public McpServerService(McpServerRepository mcpServerRepository, McpHttpClient mcpHttpClient,
		ToolService toolService, ObjectMapper objectMapper, OutboundUrlPolicy outboundUrlPolicy,
		SecretCryptoService secretCryptoService) {
		this.mcpServerRepository = mcpServerRepository;
		this.mcpHttpClient = mcpHttpClient;
		this.toolService = toolService;
		this.objectMapper = objectMapper;
		this.outboundUrlPolicy = outboundUrlPolicy;
		this.secretCryptoService = secretCryptoService;
	}

	public List<McpServerResponse> list(String assistantId) {
		return mcpServerRepository.findAllByAssistantIdOrderByCreatedAtDesc(assistantId).stream()
			.map(this::toResponse)
			.toList();
	}

	@Transactional
	public McpServerResponse create(String assistantId, McpServerRequest request) {
		mcpServerRepository.findByAssistantIdAndName(assistantId, request.name().trim()).ifPresent(existing -> {
			throw new IllegalArgumentException("MCP server name already exists for this assistant");
		});
		McpServerEntity entity = new McpServerEntity();
		entity.setAssistantId(assistantId);
		applyRequest(entity, request);
		return toResponse(mcpServerRepository.save(entity));
	}

	@Transactional
	public McpServerResponse update(String assistantId, String serverId, McpServerRequest request) {
		McpServerEntity entity = requireOwned(assistantId, serverId);
		applyRequest(entity, request);
		return toResponse(mcpServerRepository.save(entity));
	}

	@Transactional
	public void delete(String assistantId, String serverId) {
		McpServerEntity entity = requireOwned(assistantId, serverId);
		Set<String> keys = readKeys(entity.getToolKeysJson());
		for (String key : keys) {
			toolService.findByKey(key).ifPresent(tool -> {
				toolService.detachTool(assistantId, tool.getId());
				toolService.delete(tool.getId());
			});
		}
		mcpServerRepository.delete(entity);
	}

	@Transactional
	public McpSyncResponse syncTools(String assistantId, String serverId) {
		McpServerEntity entity = requireOwned(assistantId, serverId);
		if (!entity.isEnabled()) {
			throw new IllegalArgumentException("MCP server is disabled");
		}
		outboundUrlPolicy.validateHttpUrl(entity.getServerUrl());
		Map<String, String> headers = openHeaders(entity.getAuthHeadersJson());
		outboundUrlPolicy.validateHeaders(headers);
		List<McpHttpClient.McpToolInfo> remoteTools = mcpHttpClient.listTools(entity.getServerUrl(), headers);

		Set<String> previousKeys = readKeys(entity.getToolKeysJson());
		Set<String> usedKeys = new LinkedHashSet<>();
		int created = 0;
		int updated = 0;

		for (McpHttpClient.McpToolInfo remote : remoteTools) {
			String key = toolKey(assistantId, entity.getName(), remote.name());
			usedKeys.add(key);
			boolean existed = toolService.findByKey(key).isPresent();
			ToolRequest toolRequest = new ToolRequest(
				key,
				remote.name(),
				remote.description(),
				remote.inputSchema() == null ? Map.of("type", "object", "properties", Map.of()) : remote.inputSchema(),
				null,
				ToolType.MCP,
				"1",
				true,
				"mcp.call",
				Map.of(),
				Map.of(
					"mcpServerId", entity.getId(),
					"mcpToolName", remote.name(),
					"sideEffectLevel", "WRITE"));
			toolService.upsertByKey(toolRequest);
			toolService.attachToolIfAbsent(assistantId, key);
			if (existed) {
				updated++;
			}
			else {
				created++;
			}
		}

		int removed = 0;
		for (String oldKey : previousKeys) {
			if (!usedKeys.contains(oldKey)) {
				toolService.findByKey(oldKey).ifPresent(tool -> {
					toolService.detachTool(assistantId, tool.getId());
					toolService.delete(tool.getId());
				});
				removed++;
			}
		}

		entity.setToolKeysJson(writeKeys(usedKeys));
		entity.setLastSyncedAt(Instant.now());
		mcpServerRepository.save(entity);
		return new McpSyncResponse(entity.getId(), entity.getName(), created, updated, removed,
			new ArrayList<>(usedKeys));
	}

	public McpServerEntity requireOwned(String assistantId, String serverId) {
		return mcpServerRepository.findByIdAndAssistantId(serverId, assistantId)
			.orElseThrow(() -> new NotFoundException("MCP server not found"));
	}

	private void applyRequest(McpServerEntity entity, McpServerRequest request) {
		entity.setName(request.name().trim());
		String url = request.serverUrl().trim();
		outboundUrlPolicy.validateHttpUrl(url);
		entity.setServerUrl(url);
		if (request.authHeaders() != null && !request.authHeaders().isEmpty()) {
			outboundUrlPolicy.validateHeaders(request.authHeaders());
			entity.setAuthHeadersJson(sealHeaders(request.authHeaders()));
		}
		entity.setEnabled(request.enabled() == null || request.enabled());
	}

	private McpServerResponse toResponse(McpServerEntity entity) {
		Map<String, String> headers = openHeaders(entity.getAuthHeadersJson());
		Map<String, Object> redacted = new LinkedHashMap<>();
		if (!headers.isEmpty()) {
			redacted.put("configured", true);
			redacted.put("headerNames", new ArrayList<>(headers.keySet()));
		}
		else {
			redacted.put("configured", false);
			redacted.put("headerNames", List.of());
		}
		return new McpServerResponse(
			entity.getId(),
			entity.getAssistantId(),
			entity.getName(),
			entity.getServerUrl(),
			redacted,
			entity.isEnabled(),
			new ArrayList<>(readKeys(entity.getToolKeysJson())),
			entity.getLastSyncedAt(),
			entity.getCreatedAt(),
			entity.getUpdatedAt());
	}

	private static String toolKey(String assistantId, String serverName, String toolName) {
		String server = sanitize(serverName);
		String tool = sanitize(toolName);
		String shortAssistant = assistantId.length() > 8 ? assistantId.substring(0, 8) : assistantId;
		return "mcp." + shortAssistant + "." + server + "." + tool;
	}

	private static String sanitize(String value) {
		return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("(^_|_$)", "");
	}

	private Set<String> readKeys(String json) {
		if (json == null || json.isBlank()) {
			return new LinkedHashSet<>();
		}
		try {
			return new LinkedHashSet<>(objectMapper.readValue(json, new TypeReference<List<String>>() {
			}));
		}
		catch (Exception ex) {
			return new LinkedHashSet<>();
		}
	}

	private String writeKeys(Set<String> keys) {
		try {
			return objectMapper.writeValueAsString(keys);
		}
		catch (Exception ex) {
			return "[]";
		}
	}

	private Map<String, String> openHeaders(String sealedJson) {
		String json = secretCryptoService.open(sealedJson);
		if (json == null || json.isBlank()) {
			return Map.of();
		}
		try {
			return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, String>>() {
			});
		}
		catch (Exception ex) {
			return Map.of();
		}
	}

	private String sealHeaders(Map<String, String> headers) {
		if (headers == null || headers.isEmpty()) {
			return null;
		}
		try {
			return secretCryptoService.seal(objectMapper.writeValueAsString(headers));
		}
		catch (Exception ex) {
			return null;
		}
	}

	/** Used by MCP tool execution — returns decrypted headers, never exposed via API. */
	public Map<String, String> authHeadersFor(McpServerEntity entity) {
		return openHeaders(entity.getAuthHeadersJson());
	}
}
