package com.actbrow.actbrow.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.actbrow.actbrow.agent.ToolDescriptor;
import com.actbrow.actbrow.agent.ToolExecutionResult;
import com.actbrow.actbrow.model.RunEntity;
import com.actbrow.actbrow.model.ToolType;
/**
 * Progressive tool disclosure: always-on tools + compact search/activate for large catalogs.
 */
@Service
public class ProgressiveToolDisclosureService {

	public static final String TOOL_SEARCH = "tool.search";
	public static final String TOOL_ACTIVATE = "tool.activate";
	private static final String ACTIVE_KEYS_MEMORY = "activeToolKeys";

	private final RunMemoryService runMemoryService;
	private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
	private final int maxActiveSchemas;
	private final int maxActivatedPerRun;

	public ProgressiveToolDisclosureService(RunMemoryService runMemoryService,
		com.fasterxml.jackson.databind.ObjectMapper objectMapper,
		@Value("${actbrow.agent.max-active-tool-schemas:25}") int maxActiveSchemas,
		@Value("${actbrow.agent.max-activated-tools-per-run:40}") int maxActivatedPerRun) {
		this.runMemoryService = runMemoryService;
		this.objectMapper = objectMapper;
		this.maxActiveSchemas = Math.max(8, maxActiveSchemas);
		this.maxActivatedPerRun = Math.max(maxActiveSchemas, maxActivatedPerRun);
	}

	public List<ToolDescriptor> selectForPlanning(RunEntity run, List<ToolDescriptor> catalog) {
		return selectForPlanning(run, catalog, runMemoryService.getSnapshot(run.getId()));
	}

	/**
	 * @param memory a snapshot the caller already loaded, so the run loop reads {@code run_memories}
	 *               once per step instead of once here and again in {@link ContextAssembler}.
	 */
	public List<ToolDescriptor> selectForPlanning(RunEntity run, List<ToolDescriptor> catalog,
		RunMemoryService.RunMemorySnapshot memory) {
		Map<String, ToolDescriptor> byKey = index(catalog);
		Set<String> active = new LinkedHashSet<>(activeKeysFrom(memory));
		for (ToolDescriptor tool : catalog) {
			if (isAlwaysOn(tool)) {
				active.add(tool.key());
			}
		}
		ensureMetaTools(active, byKey);

		// Seed a small number of catalog tools when nothing has been activated yet.
		if (active.stream().noneMatch(key -> {
			ToolDescriptor tool = byKey.get(key);
			return tool != null && !isAlwaysOn(tool) && !isMetaTool(tool.key());
		})) {
			catalog.stream()
				.filter(tool -> !isAlwaysOn(tool) && !isMetaTool(tool.key()))
				.limit(Math.min(8, maxActiveSchemas))
				.forEach(tool -> active.add(tool.key()));
		}

		List<ToolDescriptor> selected = new ArrayList<>();
		for (String key : active) {
			ToolDescriptor tool = byKey.get(key);
			if (tool != null) {
				selected.add(tool);
			}
			if (selected.size() >= maxActiveSchemas) {
				break;
			}
		}
		writeActiveKeys(run, active);
		return selected;
	}

	public boolean handles(String toolKey) {
		return TOOL_SEARCH.equals(toolKey) || TOOL_ACTIVATE.equals(toolKey);
	}

	@SuppressWarnings("unchecked")
	public ToolExecutionResult execute(RunEntity run, List<ToolDescriptor> catalog, String toolKey,
		Map<String, Object> arguments) {
		if (TOOL_SEARCH.equals(toolKey)) {
			return search(catalog, arguments);
		}
		if (TOOL_ACTIVATE.equals(toolKey)) {
			return activate(run, catalog, arguments);
		}
		return new ToolExecutionResult(false, null, "Unknown meta tool", "Unknown meta tool");
	}

	public List<ToolDescriptor> metaToolDescriptors() {
		return List.of(metaToolByKey(TOOL_SEARCH).orElseThrow(), metaToolByKey(TOOL_ACTIVATE).orElseThrow());
	}

	public static java.util.Optional<ToolDescriptor> metaToolByKey(String key) {
		String searchSchema = """
			{"type":"object","properties":{"query":{"type":"string","description":"Keywords to match against tool key/name/description"},"domain":{"type":"string","description":"Optional domain/tag filter"},"sideEffect":{"type":"string","description":"Optional READ or WRITE filter"},"limit":{"type":"integer","description":"Max results (default 8)"}},"required":["query"]}
			""";
		String activateSchema = """
			{"type":"object","properties":{"keys":{"type":"array","items":{"type":"string"},"description":"Tool keys returned by tool.search"}},"required":["keys"]}
			""";
		if (TOOL_SEARCH.equals(key)) {
			return java.util.Optional.of(new ToolDescriptor(null, TOOL_SEARCH,
				"Search the assistant tool catalog by keyword. Returns compact tool cards (no full schemas). "
					+ "Use tool.activate to expose matching tools for calling.",
				searchSchema.trim(), ToolType.BUILD_IN, TOOL_SEARCH, Map.of(), Map.of()));
		}
		if (TOOL_ACTIVATE.equals(key)) {
			return java.util.Optional.of(new ToolDescriptor(null, TOOL_ACTIVATE,
				"Activate tool keys for this run so their full schemas become available for calling.",
				activateSchema.trim(), ToolType.BUILD_IN, TOOL_ACTIVATE, Map.of(), Map.of()));
		}
		return java.util.Optional.empty();
	}

	private ToolExecutionResult search(List<ToolDescriptor> catalog, Map<String, Object> arguments) {
		String query = arguments == null || arguments.get("query") == null ? ""
			: String.valueOf(arguments.get("query")).toLowerCase(Locale.ROOT);
		String domain = arguments == null || arguments.get("domain") == null ? ""
			: String.valueOf(arguments.get("domain")).toLowerCase(Locale.ROOT);
		String sideEffect = arguments == null || arguments.get("sideEffect") == null ? ""
			: String.valueOf(arguments.get("sideEffect")).toUpperCase(Locale.ROOT);
		int limit = 8;
		if (arguments != null && arguments.get("limit") instanceof Number number) {
			limit = Math.max(1, Math.min(20, number.intValue()));
		}
		List<Map<String, Object>> cards = new ArrayList<>();
		for (ToolDescriptor tool : catalog) {
			if (isMetaTool(tool.key())) {
				continue;
			}
			String haystack = (tool.key() + " " + nullToEmpty(tool.description())).toLowerCase(Locale.ROOT);
			if (!query.isBlank() && !haystack.contains(query)) {
				continue;
			}
			if (!domain.isBlank() && !haystack.contains(domain)) {
				continue;
			}
			String level = sideEffectLevel(tool);
			if (!sideEffect.isBlank() && !sideEffect.equals(level)) {
				continue;
			}
			Map<String, Object> card = new LinkedHashMap<>();
			card.put("key", tool.key());
			card.put("description", truncate(tool.description(), 180));
			card.put("type", tool.type() == null ? null : tool.type().name());
			card.put("sideEffectLevel", level);
			cards.add(card);
			if (cards.size() >= limit) {
				break;
			}
		}
		try {
			return new ToolExecutionResult(true, objectMapperWrite(cards), "Found " + cards.size() + " tools", null);
		}
		catch (Exception ex) {
			return new ToolExecutionResult(false, null, "tool.search failed", ex.getMessage());
		}
	}

	@SuppressWarnings("unchecked")
	private ToolExecutionResult activate(RunEntity run, List<ToolDescriptor> catalog, Map<String, Object> arguments) {
		Object keysObj = arguments == null ? null : arguments.get("keys");
		if (!(keysObj instanceof List<?> keys) || keys.isEmpty()) {
			return new ToolExecutionResult(false, null, "keys required", "keys required");
		}
		Map<String, ToolDescriptor> byKey = index(catalog);
		Set<String> active = new LinkedHashSet<>(readActiveKeys(run.getId()));
		List<String> activated = new ArrayList<>();
		for (Object keyObj : keys) {
			if (keyObj == null) {
				continue;
			}
			String key = String.valueOf(keyObj);
			if (!byKey.containsKey(key) || isMetaTool(key)) {
				continue;
			}
			if (active.size() >= maxActivatedPerRun) {
				break;
			}
			if (active.add(key)) {
				activated.add(key);
			}
		}
		writeActiveKeys(run, active);
		try {
			return new ToolExecutionResult(true,
				objectMapperWrite(Map.of("activated", activated, "activeCount", active.size())),
				"Activated " + activated.size() + " tools", null);
		}
		catch (Exception ex) {
			return new ToolExecutionResult(false, null, "tool.activate failed", ex.getMessage());
		}
	}

	private String objectMapperWrite(Object value) throws Exception {
		return objectMapper.writeValueAsString(value);
	}

	private void ensureMetaTools(Set<String> active, Map<String, ToolDescriptor> byKey) {
		for (ToolDescriptor meta : metaToolDescriptors()) {
			byKey.putIfAbsent(meta.key(), meta);
			active.add(meta.key());
		}
	}

	private static boolean isAlwaysOn(ToolDescriptor tool) {
		if (tool.type() == ToolType.BUILD_IN) {
			return true;
		}
		String key = tool.key();
		return "app.navigate".equals(key) || "path.find".equals(key) || "page.screenshot".equals(key)
			|| "knowledge.search".equals(key) || isMetaTool(key);
	}

	private static boolean isMetaTool(String key) {
		return TOOL_SEARCH.equals(key) || TOOL_ACTIVATE.equals(key);
	}

	private static Map<String, ToolDescriptor> index(List<ToolDescriptor> catalog) {
		Map<String, ToolDescriptor> byKey = new LinkedHashMap<>();
		for (ToolDescriptor tool : catalog) {
			byKey.put(tool.key(), tool);
		}
		return byKey;
	}

	private List<String> readActiveKeys(String runId) {
		return activeKeysFrom(runMemoryService.getSnapshot(runId));
	}

	/** Pure read of the active-key list from an already-loaded snapshot — issues no query. */
	private List<String> activeKeysFrom(RunMemoryService.RunMemorySnapshot snapshot) {
		Object value = snapshot.summary() == null ? null : snapshot.summary().get(ACTIVE_KEYS_MEMORY);
		if (value instanceof List<?> list) {
			List<String> keys = new ArrayList<>();
			for (Object item : list) {
				if (item != null) {
					keys.add(String.valueOf(item));
				}
			}
			return keys;
		}
		return List.of();
	}

	private void writeActiveKeys(RunEntity run, Set<String> active) {
		try {
			runMemoryService.mergeSummary(run, Map.of(ACTIVE_KEYS_MEMORY, new ArrayList<>(active)));
		}
		catch (Exception ignored) {
			// Memory merge is best-effort; planning can continue with the in-memory set.
		}
	}

	/** Delegates to {@link ToolContract} so search filters and policy never classify a tool differently. */
	private static String sideEffectLevel(ToolDescriptor tool) {
		return ToolContract.inferSideEffectLevel(tool).name();
	}

	private static String truncate(String value, int max) {
		if (value == null) {
			return "";
		}
		return value.length() <= max ? value : value.substring(0, max - 1) + "…";
	}

	private static String nullToEmpty(String value) {
		return value == null ? "" : value;
	}
}
