package com.actbrow.actbrow.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.actbrow.actbrow.api.dto.ApiIntegrationResponse;
import com.actbrow.actbrow.api.dto.ImportApiSpecRequest;
import com.actbrow.actbrow.api.dto.ImportApiSpecResponse;
import com.actbrow.actbrow.model.ApiIntegrationEntity;
import com.actbrow.actbrow.model.ToolType;
import com.actbrow.actbrow.repository.ApiIntegrationRepository;
import com.actbrow.actbrow.api.dto.ToolRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;

/**
 * Parses an uploaded Swagger/OpenAPI spec and generates one browser-executed HTTP tool per
 * operation, attaching them all to the assistant. Tools are grouped under an
 * {@link ApiIntegrationEntity} so a whole import can be listed and deleted as a unit.
 *
 * <p>Reuses {@link ToolService#upsertByKey} + {@link ToolService#attachToolIfAbsent}, the same
 * path {@code AssistantSyncService.syncHttpTools} uses for hand-written HTTP tools.
 */
@Service
public class OpenApiImportService {

	private final ToolService toolService;
	private final ApiIntegrationRepository integrationRepository;
	private final ObjectMapper objectMapper;

	public OpenApiImportService(ToolService toolService, ApiIntegrationRepository integrationRepository,
		ObjectMapper objectMapper) {
		this.toolService = toolService;
		this.integrationRepository = integrationRepository;
		this.objectMapper = objectMapper;
	}

	@Transactional
	public ImportApiSpecResponse importSpec(String assistantId, ImportApiSpecRequest request) {
		OpenAPI openApi = parse(request.specContent());
		String baseUrl = resolveBaseUrl(openApi, request.baseUrlOverride());
		boolean allowCrossOrigin = request.allowCrossOrigin() == null || request.allowCrossOrigin();

		ApiIntegrationEntity integration = integrationRepository
			.findByAssistantIdAndName(assistantId, request.name())
			.orElseGet(ApiIntegrationEntity::new);
		integration.setAssistantId(assistantId);
		integration.setName(request.name());
		integration.setBaseUrl(baseUrl);
		integration.setAllowCrossOrigin(allowCrossOrigin);
		// Save first so generated tools can carry the integration id in their metadata.
		integration = integrationRepository.save(integration);

		Set<String> previousKeys = readKeys(integration.getToolKeysJson());
		Set<String> usedKeys = new LinkedHashSet<>();
		int created = 0;
		int updated = 0;

		if (openApi.getPaths() != null) {
			for (Map.Entry<String, PathItem> pathEntry : openApi.getPaths().entrySet()) {
				String path = pathEntry.getKey();
				PathItem pathItem = pathEntry.getValue();
				if (pathItem == null) {
					continue;
				}
				for (Map.Entry<PathItem.HttpMethod, Operation> opEntry : pathItem.readOperationsMap().entrySet()) {
					String method = opEntry.getKey().name();
					Operation operation = opEntry.getValue();
					String key = uniqueKey(assistantId, request.name(), method, path, operation, usedKeys);
					usedKeys.add(key);

					boolean existed = toolService.findByKey(key).isPresent();
					ToolRequest toolRequest = buildToolRequest(integration.getId(), baseUrl, allowCrossOrigin, key,
						method, path, operation);
					toolService.upsertByKey(toolRequest);
					toolService.attachToolIfAbsent(assistantId, key);
					if (existed) {
						updated++;
					}
					else {
						created++;
					}
				}
			}
		}

		// Remove tools that existed in a prior import of this integration but are gone from the new spec.
		int removed = 0;
		for (String oldKey : previousKeys) {
			if (!usedKeys.contains(oldKey)) {
				toolService.deleteByKeyIfPresent(oldKey);
				removed++;
			}
		}

		integration.setToolKeysJson(writeKeys(usedKeys));
		integrationRepository.save(integration);

		return new ImportApiSpecResponse(integration.getId(), request.name(), created, updated, removed,
			new ArrayList<>(usedKeys));
	}

	private OpenAPI parse(String content) {
		ParseOptions options = new ParseOptions();
		options.setResolve(true);
		options.setResolveFully(true);
		SwaggerParseResult result = new OpenAPIV3Parser().readContents(content, null, options);
		OpenAPI openApi = result.getOpenAPI();
		if (openApi == null) {
			List<String> messages = result.getMessages() == null ? List.of() : result.getMessages();
			throw new IllegalArgumentException("Could not parse OpenAPI/Swagger spec: " + String.join("; ", messages));
		}
		return openApi;
	}

	private String resolveBaseUrl(OpenAPI openApi, String override) {
		if (override != null && !override.isBlank()) {
			String trimmed = override.trim();
			if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
				throw new IllegalArgumentException("baseUrlOverride must be an absolute http(s) URL");
			}
			return stripTrailingSlash(trimmed);
		}
		List<Server> servers = openApi.getServers();
		if (servers != null && !servers.isEmpty() && servers.get(0).getUrl() != null) {
			return stripTrailingSlash(servers.get(0).getUrl().trim());
		}
		return "";
	}

	private static String stripTrailingSlash(String url) {
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}

	private ToolRequest buildToolRequest(String integrationId, String baseUrl, boolean allowCrossOrigin, String key,
		String method, String path, Operation operation) {
		Map<String, Object> properties = new LinkedHashMap<>();
		Set<String> required = new LinkedHashSet<>();
		List<Map<String, Object>> parameterMeta = new ArrayList<>();

		if (operation.getParameters() != null) {
			for (Parameter parameter : operation.getParameters()) {
				String in = parameter.getIn();
				if (in == null || "cookie".equalsIgnoreCase(in)) {
					// Cookies are carried by the browser automatically.
					continue;
				}
				String name = parameter.getName();
				if (name == null || name.isBlank()) {
					continue;
				}
				properties.put(name, schemaToJsonSchema(parameter.getSchema(), parameter.getDescription()));
				if (Boolean.TRUE.equals(parameter.getRequired())) {
					required.add(name);
				}
				Map<String, Object> meta = new LinkedHashMap<>();
				meta.put("name", name);
				meta.put("in", in.toLowerCase(Locale.ROOT));
				meta.put("required", Boolean.TRUE.equals(parameter.getRequired()));
				parameterMeta.add(meta);
			}
		}

		Schema<?> bodySchema = requestBodySchema(operation.getRequestBody());
		if (bodySchema != null && bodySchema.getProperties() != null) {
			List<?> bodyRequired = bodySchema.getRequired();
			@SuppressWarnings("unchecked")
			Map<String, Schema<?>> bodyProps = (Map<String, Schema<?>>) (Map<?, ?>) bodySchema.getProperties();
			for (Map.Entry<String, Schema<?>> entry : bodyProps.entrySet()) {
				String propName = entry.getKey();
				if (properties.containsKey(propName)) {
					continue;
				}
				properties.put(propName, schemaToJsonSchema(entry.getValue(), null));
				if (bodyRequired != null && bodyRequired.contains(propName)) {
					required.add(propName);
				}
			}
		}

		Map<String, Object> inputSchema = new LinkedHashMap<>();
		inputSchema.put("type", "object");
		inputSchema.put("properties", properties);
		if (!required.isEmpty()) {
			inputSchema.put("required", new ArrayList<>(required));
		}

		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("integrationId", integrationId);
		metadata.put("method", method);
		metadata.put("baseUrl", baseUrl);
		metadata.put("path", path);
		metadata.put("execution", "browser");
		metadata.put("credentials", "include");
		metadata.put("allowCrossOrigin", allowCrossOrigin);
		metadata.put("parameters", parameterMeta);

		return new ToolRequest(key, displayName(operation, method, path), description(operation, method, path),
			inputSchema, null, ToolType.SERVER_HTTP, "1", true, key, Map.of(), metadata);
	}

	private Schema<?> requestBodySchema(RequestBody requestBody) {
		if (requestBody == null || requestBody.getContent() == null || requestBody.getContent().isEmpty()) {
			return null;
		}
		MediaType mediaType = requestBody.getContent().get("application/json");
		if (mediaType == null) {
			mediaType = requestBody.getContent().values().iterator().next();
		}
		return mediaType == null ? null : mediaType.getSchema();
	}

	private Map<String, Object> schemaToJsonSchema(Schema<?> schema, String fallbackDescription) {
		Map<String, Object> node = new LinkedHashMap<>();
		if (schema == null) {
			node.put("type", "string");
			if (fallbackDescription != null) {
				node.put("description", fallbackDescription);
			}
			return node;
		}
		String type = schema.getType();
		node.put("type", type == null ? "string" : type);
		String description = schema.getDescription() != null ? schema.getDescription() : fallbackDescription;
		if (description != null) {
			node.put("description", description);
		}
		if (schema.getFormat() != null) {
			node.put("format", schema.getFormat());
		}
		if (schema.getEnum() != null && !schema.getEnum().isEmpty()) {
			node.put("enum", schema.getEnum());
		}
		if ("array".equals(type) && schema.getItems() != null) {
			node.put("items", schemaToJsonSchema(schema.getItems(), null));
		}
		return node;
	}

	private String uniqueKey(String assistantId, String integrationName, String method, String path,
		Operation operation, Set<String> used) {
		String base;
		if (operation.getOperationId() != null && !operation.getOperationId().isBlank()) {
			base = operation.getOperationId();
		}
		else {
			base = method + "_" + path;
		}
		// Prefix with a stable per-assistant discriminator so the globally-unique tool_key cannot
		// collide across assistants that happen to pick the same integration name.
		String candidate = assistantPrefix(assistantId) + "_" + sanitize(integrationName) + "." + sanitize(base);
		String key = candidate;
		int counter = 2;
		while (used.contains(key)) {
			key = candidate + "_" + counter++;
		}
		return key;
	}

	private static String assistantPrefix(String assistantId) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(assistantId.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder();
			for (int i = 0; i < 4; i++) {
				hex.append(String.format("%02x", hash[i]));
			}
			return hex.toString();
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 unavailable", exception);
		}
	}

	private static String sanitize(String value) {
		String lowered = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
		lowered = lowered.replaceAll("^_+", "").replaceAll("_+$", "");
		return lowered.isEmpty() ? "op" : lowered;
	}

	private static String displayName(Operation operation, String method, String path) {
		String name;
		if (operation.getSummary() != null && !operation.getSummary().isBlank()) {
			name = operation.getSummary();
		}
		else if (operation.getOperationId() != null && !operation.getOperationId().isBlank()) {
			name = operation.getOperationId();
		}
		else {
			name = method + " " + path;
		}
		// displayName column is varchar(255); long OpenAPI summaries would overflow on Postgres.
		return name.length() > 255 ? name.substring(0, 255) : name;
	}

	private static String description(Operation operation, String method, String path) {
		String summary = operation.getSummary();
		String detail = operation.getDescription();
		StringBuilder builder = new StringBuilder();
		if (summary != null && !summary.isBlank()) {
			builder.append(summary);
		}
		if (detail != null && !detail.isBlank()) {
			if (builder.length() > 0) {
				builder.append(" — ");
			}
			builder.append(detail);
		}
		if (builder.length() == 0) {
			builder.append(method).append(' ').append(path);
		}
		String text = builder.toString();
		return text.length() > 2_000 ? text.substring(0, 2_000) : text;
	}

	private Set<String> readKeys(String json) {
		if (json == null || json.isBlank()) {
			return new LinkedHashSet<>();
		}
		try {
			List<String> keys = objectMapper.readValue(json, new TypeReference<List<String>>() {
			});
			return new LinkedHashSet<>(keys);
		}
		catch (JsonProcessingException exception) {
			return new LinkedHashSet<>();
		}
	}

	private String writeKeys(Set<String> keys) {
		try {
			return objectMapper.writeValueAsString(new ArrayList<>(keys));
		}
		catch (JsonProcessingException exception) {
			throw new IllegalArgumentException("Unable to serialize tool keys", exception);
		}
	}

	public List<ApiIntegrationResponse> listIntegrations(String assistantId) {
		return integrationRepository.findAllByAssistantIdOrderByCreatedAtDesc(assistantId).stream()
			.map(this::toResponse)
			.toList();
	}

	private ApiIntegrationResponse toResponse(ApiIntegrationEntity entity) {
		return new ApiIntegrationResponse(entity.getId(), entity.getAssistantId(), entity.getName(),
			entity.getBaseUrl(), entity.isAllowCrossOrigin(), new ArrayList<>(readKeys(entity.getToolKeysJson())),
			entity.getCreatedAt(), entity.getUpdatedAt());
	}

	@Transactional
	public void deleteIntegration(String assistantId, String integrationId) {
		Optional<ApiIntegrationEntity> found = integrationRepository.findById(integrationId);
		if (found.isEmpty()) {
			// Idempotent: already gone.
			return;
		}
		ApiIntegrationEntity integration = found.get();
		if (!integration.getAssistantId().equals(assistantId)) {
			// Don't reveal existence of another assistant's integration.
			throw new IllegalArgumentException("Integration not found");
		}
		for (String key : readKeys(integration.getToolKeysJson())) {
			toolService.deleteByKeyIfPresent(key);
		}
		integrationRepository.delete(integration);
	}
}
