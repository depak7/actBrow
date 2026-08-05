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
		List<String> queryTerms = tokenize(query);
		List<ScoredTool> scored = new ArrayList<>();
		for (ToolDescriptor tool : catalog) {
			if (isMetaTool(tool.key())) {
				continue;
			}
			String level = sideEffectLevel(tool);
			if (!sideEffect.isBlank() && !sideEffect.equals(level)) {
				continue;
			}
			String haystack = (tool.key() + " " + nullToEmpty(tool.description())).toLowerCase(Locale.ROOT);
			// Domain stays a hard filter — it is an explicit narrowing, not a relevance hint.
			if (!domain.isBlank() && !haystack.contains(domain)) {
				continue;
			}
			double score = score(tool, queryTerms);
			if (score <= 0 && !queryTerms.isEmpty()) {
				continue;
			}
			scored.add(new ScoredTool(tool, level, score));
		}
		// Best match first. Ties fall back to catalog order, which is stable across calls.
		scored.sort((left, right) -> Double.compare(right.score(), left.score()));

		List<Map<String, Object>> cards = new ArrayList<>();
		for (ScoredTool entry : scored) {
			Map<String, Object> card = new LinkedHashMap<>();
			card.put("key", entry.tool().key());
			card.put("description", truncate(entry.tool().description(), 180));
			card.put("type", entry.tool().type() == null ? null : entry.tool().type().name());
			card.put("sideEffectLevel", entry.level());
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
		return "app.navigate".equals(key) || "path.find".equals(key) || "page.observe".equals(key)
			|| "page.screenshot".equals(key)
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

	private record ScoredTool(ToolDescriptor tool, String level, double score) {
	}

	/**
	 * Splits a phrase into lowercase terms, treating dots, underscores and hyphens as separators so a
	 * tool key like {@code orders.refund.create} matches a natural-language query like "refund order".
	 * Single-character fragments are dropped as noise.
	 */
	private static List<String> tokenize(String value) {
		if (value == null || value.isBlank()) {
			return List.of();
		}
		List<String> terms = new ArrayList<>();
		for (String raw : value.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
			if (raw.length() > 1) {
				terms.add(raw);
			}
		}
		return terms;
	}

	/**
	 * Relevance of a tool to the query, by term overlap.
	 *
	 * <p>This replaced a whole-string {@code haystack.contains(query)} test, which required the entire
	 * query phrase to appear verbatim — so a model asking for "refund a customer order" matched
	 * nothing, and with a large catalog the agent would conclude a capability it actually has does not
	 * exist. Key matches are weighted above description matches because the key is the operator's
	 * deliberate naming, while descriptions are prose and match more loosely.
	 */
	private static double score(ToolDescriptor tool, List<String> queryTerms) {
		if (queryTerms.isEmpty()) {
			return 1;
		}
		List<String> keyTerms = tokenize(tool.key());
		List<String> descriptionTerms = tokenize(nullToEmpty(tool.description()));
		double raw = 0;
		for (String term : queryTerms) {
			if (keyTerms.contains(term)) {
				raw += 3;
			}
			else if (descriptionTerms.contains(term)) {
				raw += 1;
			}
			else if (keyTerms.stream().anyMatch(k -> k.startsWith(term) || term.startsWith(k))) {
				// Catches plural/stem mismatches such as "orders" vs "order".
				raw += 1.5;
			}
		}
		if (raw == 0) {
			// No overlap at all. Must return exactly zero so the caller drops the tool — adding the
			// tie-breaker below unconditionally would make every tool in the catalog "relevant".
			return 0;
		}
		// Normalise by query length so a long query cannot out-score a short precise one purely on
		// term count, then nudge shorter keys up as they are usually the more general tool.
		return raw / queryTerms.size() + 0.01 / keyTerms.size();
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
