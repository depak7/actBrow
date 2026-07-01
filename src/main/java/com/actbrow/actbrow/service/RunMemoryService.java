package com.actbrow.actbrow.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.actbrow.actbrow.agent.FinalResponseDecision;
import com.actbrow.actbrow.agent.ModelDecision;
import com.actbrow.actbrow.agent.ToolCall;
import com.actbrow.actbrow.agent.ToolCallDecision;
import com.actbrow.actbrow.agent.ToolDescriptor;
import com.actbrow.actbrow.agent.ToolExecutionResult;
import com.actbrow.actbrow.conversation.PageContextParser;
import com.actbrow.actbrow.conversation.UserMessageDisplay;
import com.actbrow.actbrow.model.RunEntity;
import com.actbrow.actbrow.model.RunMemoryEntity;
import com.actbrow.actbrow.repository.RunMemoryRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class RunMemoryService {

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};
	private static final TypeReference<List<Map<String, Object>>> LIST_OF_MAPS_TYPE = new TypeReference<>() {
	};

	private final RunMemoryRepository runMemoryRepository;
	private final ObjectMapper objectMapper;

	public RunMemoryService(RunMemoryRepository runMemoryRepository, ObjectMapper objectMapper) {
		this.runMemoryRepository = runMemoryRepository;
		this.objectMapper = objectMapper;
	}

	@Transactional
	public void initializeForRun(RunEntity run, String storedUserContent) {
		RunMemoryEntity entity = runMemoryRepository.findByRunId(run.getId()).orElseGet(RunMemoryEntity::new);
		entity.setRunId(run.getId());
		entity.setConversationId(run.getConversationId());
		String objective = compact(UserMessageDisplay.stripStoredAppendix(storedUserContent), 1_200);
		entity.setObjective(objective);
		entity.setCurrentStepGoal("Understand the user's latest request and choose the next best action.");
		entity.setSuccessCriteria("Complete the user's latest request accurately, using tools only when needed.");
		entity.setKnownEntitiesJson(toJson(extractInitialEntities(storedUserContent)));
		entity.setLastActionJson(toJson(Map.of(
			"kind", "run_initialized",
			"objective", objective
		)));
		entity.setLastFailuresJson("[]");
		entity.setBlockedReason(null);
		Map<String, Object> initialSummary = new LinkedHashMap<>();
		initialSummary.put("status", "initialized");
		String latestUserPath = PageContextParser.extractPath(storedUserContent);
		if (latestUserPath != null && !latestUserPath.isBlank()) {
			initialSummary.put("latestUserPath", latestUserPath);
		}
		entity.setSummaryJson(toJson(initialSummary));
		runMemoryRepository.save(entity);
	}

	@Transactional
	public void recordModelDecision(RunEntity run, ModelDecision decision, int stepIndex) {
		RunMemoryEntity entity = requireOrInitialize(run);
		if (decision instanceof ToolCallDecision toolCallDecision) {
			List<Map<String, Object>> plannedTools = toolCallDecision.toolCalls().stream()
				.map(call -> Map.of(
					"toolKey", call.toolKey(),
					"arguments", call.arguments() == null ? Map.of() : call.arguments()))
				.toList();
			entity.setCurrentStepGoal("Execute planned tool call(s) for step " + (stepIndex + 1) + ".");
			entity.setLastActionJson(toJson(Map.of(
				"kind", "model_decision",
				"stepIndex", stepIndex,
				"decisionType", "tool_call",
				"plannedTools", plannedTools
			)));
		}
		else if (decision instanceof FinalResponseDecision finalDecision) {
			entity.setCurrentStepGoal("Finalize the answer for the user.");
			entity.setLastActionJson(toJson(Map.of(
				"kind", "model_decision",
				"stepIndex", stepIndex,
				"decisionType", "final_response",
				"messagePreview", compact(finalDecision.message(), 300)
			)));
		}
		runMemoryRepository.save(entity);
	}

	@Transactional
	public void recordToolResult(RunEntity run, ToolCall toolCall, ToolDescriptor tool, Map<String, Object> arguments,
		ToolExecutionResult result, int stepIndex) {
		RunMemoryEntity entity = requireOrInitialize(run);
		Map<String, Object> knownEntities = new LinkedHashMap<>(parseMap(entity.getKnownEntitiesJson()));
		knownEntities.putAll(extractActionEntities(arguments));
		knownEntities.putAll(extractStructuredEntities(result));
		entity.setKnownEntitiesJson(toJson(knownEntities));

		Map<String, Object> lastAction = new LinkedHashMap<>();
		lastAction.put("kind", "tool_result");
		lastAction.put("stepIndex", stepIndex);
		lastAction.put("toolCallId", toolCall.toolCallId());
		lastAction.put("toolKey", tool.key());
		lastAction.put("success", result.success());
		lastAction.put("arguments", arguments == null ? Map.of() : arguments);
		lastAction.put("textSummary", compact(result.textSummary(), 500));
		lastAction.put("error", compact(result.error(), 500));
		entity.setLastActionJson(toJson(lastAction));

		List<Map<String, Object>> failures = new ArrayList<>(parseFailures(entity.getLastFailuresJson()));
		if (!result.success()) {
			failures.add(Map.of(
				"toolKey", tool.key(),
				"toolCallId", toolCall.toolCallId(),
				"stepIndex", stepIndex,
				"error", compact(firstNonBlank(result.error(), result.textSummary(), result.structuredOutput()), 700)
			));
			while (failures.size() > 4) {
				failures.remove(0);
			}
			entity.setBlockedReason(compact(firstNonBlank(result.error(), result.textSummary()), 1_000));
		}
		else {
			entity.setBlockedReason(null);
		}
		entity.setLastFailuresJson(toJson(failures));
		entity.setCurrentStepGoal(result.success()
			? "Use the latest successful tool result to continue toward the user's objective."
			: "Recover from the latest tool failure without repeating the same broken action.");
		entity.setSummaryJson(toJson(buildSummary(entity.getObjective(), knownEntities, failures, result)));
		runMemoryRepository.save(entity);
	}

	@Transactional
	public void recordFinalResponse(RunEntity run, String message) {
		RunMemoryEntity entity = requireOrInitialize(run);
		entity.setCurrentStepGoal("Run completed.");
		entity.setBlockedReason(null);
		entity.setSummaryJson(toJson(Map.of(
			"status", "completed",
			"objective", entity.getObjective(),
			"assistantMessagePreview", compact(message, 400)
		)));
		runMemoryRepository.save(entity);
	}

	@Transactional
	public void recordRunFailure(String runId, String reason) {
		runMemoryRepository.findByRunId(runId).ifPresent(entity -> {
			entity.setBlockedReason(compact(reason, 1_000));
			entity.setCurrentStepGoal("Run ended with a failure.");
			entity.setSummaryJson(toJson(Map.of(
				"status", "failed",
				"objective", entity.getObjective(),
				"blockedReason", compact(reason, 500)
			)));
			runMemoryRepository.save(entity);
		});
	}

	@Transactional(readOnly = true)
	public RunMemorySnapshot getSnapshot(String runId) {
		return runMemoryRepository.findByRunId(runId)
			.map(this::toSnapshot)
			.orElse(RunMemorySnapshot.empty());
	}

	@Transactional
	public void deleteByRunId(String runId) {
		runMemoryRepository.deleteByRunId(runId);
	}

	@Transactional
	public void deleteByConversationId(String conversationId) {
		runMemoryRepository.deleteByConversationId(conversationId);
	}

	private RunMemoryEntity requireOrInitialize(RunEntity run) {
		return runMemoryRepository.findByRunId(run.getId()).orElseGet(() -> {
			RunMemoryEntity entity = new RunMemoryEntity();
			entity.setRunId(run.getId());
			entity.setConversationId(run.getConversationId());
			entity.setObjective("");
			entity.setCurrentStepGoal("Continue the run.");
			entity.setSuccessCriteria("Complete the user's latest request accurately.");
			entity.setKnownEntitiesJson("{}");
			entity.setLastActionJson("{}");
			entity.setLastFailuresJson("[]");
			entity.setSummaryJson("{}");
			return entity;
		});
	}

	private RunMemorySnapshot toSnapshot(RunMemoryEntity entity) {
		return new RunMemorySnapshot(
			nullToEmpty(entity.getObjective()),
			nullToEmpty(entity.getCurrentStepGoal()),
			nullToEmpty(entity.getSuccessCriteria()),
			parseMap(entity.getKnownEntitiesJson()),
			parseMap(entity.getLastActionJson()),
			parseFailures(entity.getLastFailuresJson()),
			entity.getBlockedReason(),
			parseMap(entity.getSummaryJson()));
	}

	private Map<String, Object> extractInitialEntities(String storedUserContent) {
		Map<String, Object> entities = new LinkedHashMap<>();
		String path = PageContextParser.extractPath(storedUserContent);
		if (path != null && !path.isBlank()) {
			entities.put("path", path);
		}
		String objective = compact(UserMessageDisplay.stripStoredAppendix(storedUserContent), 200);
		if (!objective.isBlank()) {
			entities.put("user_request", objective);
		}
		return entities;
	}

	private Map<String, Object> extractActionEntities(Map<String, Object> arguments) {
		Map<String, Object> entities = new LinkedHashMap<>();
		if (arguments == null) {
			return entities;
		}
		for (Map.Entry<String, Object> entry : arguments.entrySet()) {
			Object value = entry.getValue();
			if (isScalar(value) && looksEntityLike(entry.getKey())) {
				entities.put(entry.getKey(), value);
			}
		}
		return entities;
	}

	private Map<String, Object> extractStructuredEntities(ToolExecutionResult result) {
		Map<String, Object> entities = new LinkedHashMap<>();
		if (result == null || result.structuredOutput() == null || result.structuredOutput().isBlank()) {
			return entities;
		}
		try {
			Map<String, Object> map = objectMapper.readValue(result.structuredOutput(), MAP_TYPE);
			for (Map.Entry<String, Object> entry : map.entrySet()) {
				Object value = entry.getValue();
				if (isScalar(value) && looksEntityLike(entry.getKey())) {
					entities.put(entry.getKey(), value);
				}
			}
		}
		catch (Exception ignored) {
			// Non-JSON structured output is fine; memory just won't extract entities from it.
		}
		return entities;
	}

	private Map<String, Object> buildSummary(String objective, Map<String, Object> knownEntities,
		List<Map<String, Object>> failures, ToolExecutionResult result) {
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("status", result.success() ? "progressing" : "blocked");
		summary.put("objective", objective);
		summary.put("knownEntityKeys", knownEntities.keySet());
		summary.put("recentFailureCount", failures.size());
		if (!result.success()) {
			summary.put("latestFailure", compact(firstNonBlank(result.error(), result.textSummary()), 300));
		}
		return summary;
	}

	private Map<String, Object> parseMap(String json) {
		if (json == null || json.isBlank()) {
			return Map.of();
		}
		try {
			return objectMapper.readValue(json, MAP_TYPE);
		}
		catch (Exception exception) {
			return Map.of();
		}
	}

	private List<Map<String, Object>> parseFailures(String json) {
		if (json == null || json.isBlank()) {
			return List.of();
		}
		try {
			return objectMapper.readValue(json, LIST_OF_MAPS_TYPE);
		}
		catch (Exception exception) {
			return List.of();
		}
	}

	private String toJson(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		}
		catch (Exception exception) {
			return "{}";
		}
	}

	private static boolean looksEntityLike(String key) {
		String normalized = key == null ? "" : key.toLowerCase();
		return normalized.endsWith("id")
			|| normalized.contains("path")
			|| normalized.contains("url")
			|| normalized.contains("email")
			|| normalized.contains("name")
			|| normalized.contains("status")
			|| normalized.contains("title");
	}

	private static boolean isScalar(Object value) {
		return value == null
			|| value instanceof String
			|| value instanceof Number
			|| value instanceof Boolean;
	}

	private static String compact(String value, int maxLength) {
		if (value == null) {
			return "";
		}
		String normalized = value.replaceAll("\\s+", " ").trim();
		if (normalized.length() <= maxLength) {
			return normalized;
		}
		return normalized.substring(0, Math.max(0, maxLength - 3)) + "...";
	}

	private static String firstNonBlank(String... values) {
		for (String value : values) {
			if (value != null && !value.isBlank()) {
				return value;
			}
		}
		return "";
	}

	private static String nullToEmpty(String value) {
		return value == null ? "" : value;
	}

	public record RunMemorySnapshot(
		String objective,
		String currentStepGoal,
		String successCriteria,
		Map<String, Object> knownEntities,
		Map<String, Object> lastAction,
		List<Map<String, Object>> lastFailures,
		String blockedReason,
		Map<String, Object> summary
	) {
		static RunMemorySnapshot empty() {
			return new RunMemorySnapshot("", "", "", Map.of(), Map.of(), List.of(), null, Map.of());
		}
	}
}
