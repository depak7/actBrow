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
 * Progressive tool disclosure + specialist agents with harness auto-routing.
 *
 * <p>Two specialists share one run: {@link ToolDomain#BROWSER} (page/navigation) and
 * {@link ToolDomain#API} (operator-added HTTP/MCP tools). Only one specialist's tools are offered
 * per planning turn. The harness auto-routes whenever intent is unambiguous:
 * <ul>
 *   <li>{@code tool.search} hits are all API (or all browser) → switch + activate those keys</li>
 *   <li>{@code tool.activate} for the other domain → switch then activate</li>
 *   <li>direct call of a real catalog tool from the other domain → switch + activate, then run</li>
 * </ul>
 * Explicit {@link #AGENT_USE_BROWSER} / {@link #AGENT_USE_API} still work when the model chooses.
 */
@Service
public class ProgressiveToolDisclosureService {

	public static final String TOOL_SEARCH = "tool.search";
	public static final String TOOL_ACTIVATE = "tool.activate";
	public static final String AGENT_USE_BROWSER = "agent.use_browser";
	public static final String AGENT_USE_API = "agent.use_api";

	private static final String ACTIVE_KEYS_MEMORY = "activeToolKeys";
	private static final String SPECIALIST_MEMORY = "specialistAgent";

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
		ToolDomain specialist = specialistFrom(memory);
		Map<String, ToolDescriptor> byKey = index(catalog);
		// Meta + agent-switch tools are synthetic and not stored on the assistant catalog.
		for (ToolDescriptor meta : metaToolDescriptors()) {
			byKey.putIfAbsent(meta.key(), meta);
		}

		Set<String> active = new LinkedHashSet<>(activeKeysFrom(memory));
		// Always-on tools for this specialist (browser defaults, knowledge, meta).
		for (ToolDescriptor tool : catalog) {
			if (isAlwaysOnFor(tool, specialist)) {
				active.add(tool.key());
			}
		}
		ensureMetaTools(active, byKey);

		// Drop keys that belong to the other specialist so a prior API activation does not leak
		// into the browser agent's schema (and vice versa).
		active.removeIf(key -> {
			ToolDescriptor tool = byKey.get(key);
			if (tool == null) {
				return true;
			}
			ToolDomain domain = ToolCatalogPolicies.domainOf(tool);
			return domain != ToolDomain.SHARED && domain != specialist;
		});

		// Seed domain tools when none of this specialist's non-always-on tools are active yet.
		// API specialist seeds aggressively so operator-added tools are in schema (not invented).
		boolean hasSpecialistTool = active.stream().anyMatch(key -> {
			ToolDescriptor tool = byKey.get(key);
			return tool != null && ToolCatalogPolicies.domainOf(tool) == specialist
				&& !isAlwaysOnFor(tool, specialist) && !isMetaOrAgentSwitchTool(tool.key());
		});
		if (!hasSpecialistTool) {
			int seedLimit = specialist == ToolDomain.API
				? Math.max(8, maxActiveSchemas - 6)
				: Math.min(8, maxActiveSchemas);
			catalog.stream()
				.filter(tool -> ToolCatalogPolicies.domainOf(tool) == specialist)
				.filter(tool -> !isAlwaysOnFor(tool, specialist) && !isMetaOrAgentSwitchTool(tool.key()))
				.limit(seedLimit)
				.forEach(tool -> active.add(tool.key()));
		}

		List<ToolDescriptor> selected = new ArrayList<>();
		// Prefer SHARED + current specialist only.
		for (String key : active) {
			ToolDescriptor tool = byKey.get(key);
			if (tool == null) {
				continue;
			}
			ToolDomain domain = ToolCatalogPolicies.domainOf(tool);
			if (domain != ToolDomain.SHARED && domain != specialist) {
				continue;
			}
			selected.add(tool);
			if (selected.size() >= maxActiveSchemas) {
				break;
			}
		}
		// Guarantee meta tools are present even if the active set was truncated.
		for (ToolDescriptor meta : metaToolDescriptors()) {
			if (selected.stream().noneMatch(t -> meta.key().equals(t.key()))) {
				if (selected.size() >= maxActiveSchemas && !selected.isEmpty()) {
					selected.remove(selected.size() - 1);
				}
				selected.add(meta);
			}
		}
		writeActiveKeys(run, active);
		writeSpecialist(run, specialist);
		return selected;
	}

	public boolean handles(String toolKey) {
		return isMetaOrAgentSwitchTool(toolKey);
	}

	@SuppressWarnings("unchecked")
	public ToolExecutionResult execute(RunEntity run, List<ToolDescriptor> catalog, String toolKey,
		Map<String, Object> arguments) {
		if (TOOL_SEARCH.equals(toolKey)) {
			return search(run, catalog, arguments);
		}
		if (TOOL_ACTIVATE.equals(toolKey)) {
			return activate(run, catalog, arguments);
		}
		if (AGENT_USE_BROWSER.equals(toolKey)) {
			return switchSpecialist(run, ToolDomain.BROWSER);
		}
		if (AGENT_USE_API.equals(toolKey)) {
			return switchSpecialist(run, ToolDomain.API);
		}
		return new ToolExecutionResult(false, null, "Unknown meta tool", "Unknown meta tool");
	}

	public List<ToolDescriptor> metaToolDescriptors() {
		return List.of(
			metaToolByKey(TOOL_SEARCH).orElseThrow(),
			metaToolByKey(TOOL_ACTIVATE).orElseThrow(),
			metaToolByKey(AGENT_USE_BROWSER).orElseThrow(),
			metaToolByKey(AGENT_USE_API).orElseThrow());
	}

	public static java.util.Optional<ToolDescriptor> metaToolByKey(String key) {
		String searchSchema = """
			{"type":"object","properties":{"query":{"type":"string","description":"Keywords to match against tool key/name/description"},"domain":{"type":"string","description":"Optional BROWSER, API, or ALL filter"},"sideEffect":{"type":"string","description":"Optional READ or WRITE filter"},"limit":{"type":"integer","description":"Max results (default 8)"}},"required":["query"]}
			""";
		String activateSchema = """
			{"type":"object","properties":{"keys":{"type":"array","items":{"type":"string"},"description":"Tool keys returned by tool.search for the active specialist"}},"required":["keys"]}
			""";
		String emptySchema = """
			{"type":"object","properties":{}}
			""";
		if (TOOL_SEARCH.equals(key)) {
			return java.util.Optional.of(new ToolDescriptor(null, TOOL_SEARCH,
				"Search the assistant tool catalog by keyword. Returns compact tool cards (no full schemas) "
					+ "with a domain tag (BROWSER/API). The harness auto-switches specialist and activates "
					+ "keys when every hit is in one domain (e.g. only API tools). You can then call those "
					+ "tools on the next step — or in the same step if you already know the keys.",
				searchSchema.trim(), ToolType.BUILD_IN, TOOL_SEARCH, Map.of(), Map.of()));
		}
		if (TOOL_ACTIVATE.equals(key)) {
			return java.util.Optional.of(new ToolDescriptor(null, TOOL_ACTIVATE,
				"Activate tool keys so their full schemas become available. If the keys belong to the other "
					+ "specialist, the harness auto-switches then activates — you do not need a separate "
					+ "agent.use_* call.",
				activateSchema.trim(), ToolType.BUILD_IN, TOOL_ACTIVATE, Map.of(), Map.of()));
		}
		if (AGENT_USE_BROWSER.equals(key)) {
			return java.util.Optional.of(new ToolDescriptor(null, AGENT_USE_BROWSER,
				"Explicitly switch to the BROWSER specialist (navigation, page.observe, path.find, screenshots). "
					+ "Usually unnecessary: the harness auto-routes when search/activate/calls are browser-only.",
				emptySchema.trim(), ToolType.BUILD_IN, AGENT_USE_BROWSER, Map.of(), Map.of()));
		}
		if (AGENT_USE_API.equals(key)) {
			return java.util.Optional.of(new ToolDescriptor(null, AGENT_USE_API,
				"Explicitly switch to the API specialist (operator HTTP/MCP tools; multi-call steps run in "
					+ "parallel). Usually unnecessary: the harness auto-routes when search hits or activates "
					+ "are API-only.",
				emptySchema.trim(), ToolType.BUILD_IN, AGENT_USE_API, Map.of(), Map.of()));
		}
		return java.util.Optional.empty();
	}

	public static boolean isMetaOrAgentSwitchTool(String key) {
		return TOOL_SEARCH.equals(key) || TOOL_ACTIVATE.equals(key)
			|| AGENT_USE_BROWSER.equals(key) || AGENT_USE_API.equals(key);
	}

	/** Active specialist for this run (defaults to BROWSER for embedded host-app use). */
	public ToolDomain currentSpecialist(RunEntity run) {
		if (run == null || run.getId() == null || run.getId().isBlank()) {
			return ToolDomain.BROWSER;
		}
		return specialistFrom(runMemoryService.getSnapshot(run.getId()));
	}

	public ToolDomain specialistFrom(RunMemoryService.RunMemorySnapshot memory) {
		if (memory == null) {
			return ToolDomain.BROWSER;
		}
		Object value = memory.summary() == null ? null : memory.summary().get(SPECIALIST_MEMORY);
		if (value == null) {
			return ToolDomain.BROWSER;
		}
		String raw = String.valueOf(value).trim().toUpperCase(Locale.ROOT);
		if ("API".equals(raw)) {
			return ToolDomain.API;
		}
		return ToolDomain.BROWSER;
	}

	/**
	 * Planner preamble loaded every step: turn contract (bounded efficiency) + active specialist.
	 * Kept modular so the base system prompt stays shorter (curse of instructions).
	 */
	public String specialistRuntimeGuidance(RunMemoryService.RunMemorySnapshot memory) {
		ToolDomain specialist = specialistFrom(memory);
		StringBuilder builder = new StringBuilder();
		builder.append(HarnessPromptContract.turnEfficiencyContract());
		builder.append("SPECIALIST HARNESS (deterministic):\n");
		builder.append("  - Active specialist: ").append(specialist.name()).append(".\n");
		if (specialist == ToolDomain.API) {
			builder.append("  - API tools are in schema (when attached/activated). Call multiple independent ");
			builder.append("HTTP/MCP tools in one step for parallel execution.\n");
			builder.append("  - Browser/page tools are hidden. Pure browser search/calls auto-route back.\n");
		}
		else {
			builder.append("  - Browser/page tools are in schema. API/HTTP tools are hidden.\n");
			builder.append("  - Pure-API tool.search / activate / direct catalog call auto-routes to API.\n");
		}
		builder.append("  - Never invent tool names. Prefer schema names or tool.search keys.\n\n");
		return builder.toString();
	}

	/**
	 * Ensure the run is on the specialist that owns {@code tool}, activate its key, and report what
	 * changed. Used when the model calls a real catalog tool that was not in this turn's schema.
	 */
	public synchronized AutoRouteResult routeForToolCall(RunEntity run, List<ToolDescriptor> catalog,
		ToolDescriptor tool) {
		if (tool == null || isMetaOrAgentSwitchTool(tool.key())) {
			return AutoRouteResult.noop(currentSpecialist(run));
		}
		ToolDomain domain = ToolCatalogPolicies.domainOf(tool);
		ToolDomain before = currentSpecialist(run);
		boolean switched = false;
		ToolDomain after = before;
		if (domain == ToolDomain.BROWSER || domain == ToolDomain.API) {
			if (domain != before) {
				applySpecialistSwitch(run, domain);
				switched = true;
				after = domain;
			}
		}
		List<String> activated = activateKeysInternal(run, catalog, after, List.of(tool.key()));
		return new AutoRouteResult(before, after, switched, activated);
	}

	public record AutoRouteResult(
		ToolDomain specialistBefore,
		ToolDomain specialistAfter,
		boolean switched,
		List<String> activated
	) {
		static AutoRouteResult noop(ToolDomain specialist) {
			return new AutoRouteResult(specialist, specialist, false, List.of());
		}
	}

	private ToolExecutionResult switchSpecialist(RunEntity run, ToolDomain specialist) {
		ToolDomain before = currentSpecialist(run);
		applySpecialistSwitch(run, specialist);
		try {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("specialist", specialist.name());
			body.put("previousSpecialist", before.name());
			body.put("autoRouted", false);
			body.put("guidance", specialist == ToolDomain.API
				? "API specialist active. Call independent HTTP/MCP tools together in one step for parallel execution."
				: "Browser specialist active. Use page/navigation tools only.");
			return new ToolExecutionResult(true, objectMapperWrite(body),
				"Switched to " + specialist.name() + " specialist", null);
		}
		catch (Exception ex) {
			return new ToolExecutionResult(true, null, "Switched to " + specialist.name() + " specialist", null);
		}
	}

	/** Clear cross-domain activations and persist the new specialist. */
	private void applySpecialistSwitch(RunEntity run, ToolDomain specialist) {
		writeSpecialist(run, specialist);
		Set<String> active = new LinkedHashSet<>(readActiveKeys(run.getId()));
		active.removeIf(key -> !isMetaOrAgentSwitchTool(key) && !"knowledge.search".equals(key));
		for (ToolDescriptor meta : metaToolDescriptors()) {
			active.add(meta.key());
		}
		writeActiveKeys(run, active);
	}

	private ToolExecutionResult search(RunEntity run, List<ToolDescriptor> catalog, Map<String, Object> arguments) {
		ToolDomain specialist = currentSpecialist(run);
		String query = arguments == null || arguments.get("query") == null ? ""
			: String.valueOf(arguments.get("query")).toLowerCase(Locale.ROOT);
		String domainFilter = arguments == null || arguments.get("domain") == null ? ""
			: String.valueOf(arguments.get("domain")).toUpperCase(Locale.ROOT);
		String sideEffect = arguments == null || arguments.get("sideEffect") == null ? ""
			: String.valueOf(arguments.get("sideEffect")).toUpperCase(Locale.ROOT);
		int limit = 8;
		if (arguments != null && arguments.get("limit") instanceof Number number) {
			limit = Math.max(1, Math.min(20, number.intValue()));
		}
		// Default: search the full catalog so operators' API tools stay discoverable while the browser
		// specialist is active. Pass domain=BROWSER or domain=API to narrow.
		ToolDomain hardDomain = null;
		if ("BROWSER".equals(domainFilter)) {
			hardDomain = ToolDomain.BROWSER;
		}
		else if ("API".equals(domainFilter)) {
			hardDomain = ToolDomain.API;
		}

		List<String> queryTerms = tokenize(query);
		List<ScoredTool> scored = new ArrayList<>();
		for (ToolDescriptor tool : catalog) {
			if (isMetaOrAgentSwitchTool(tool.key())) {
				continue;
			}
			ToolDomain domain = ToolCatalogPolicies.domainOf(tool);
			if (hardDomain != null && domain != hardDomain && domain != ToolDomain.SHARED) {
				continue;
			}
			String level = sideEffectLevel(tool);
			if (!sideEffect.isBlank() && !sideEffect.equals(level)) {
				continue;
			}
			double score = score(tool, queryTerms);
			if (score <= 0 && !queryTerms.isEmpty()) {
				continue;
			}
			if (hardDomain == null && domain == specialist) {
				score += 0.25;
			}
			scored.add(new ScoredTool(tool, level, domain, score));
		}
		scored.sort((left, right) -> Double.compare(right.score(), left.score()));

		List<ScoredTool> top = new ArrayList<>();
		for (ScoredTool entry : scored) {
			top.add(entry);
			if (top.size() >= limit) {
				break;
			}
		}

		// Pure-domain auto-route: every non-SHARED hit is BROWSER or every hit is API.
		ToolDomain pureDomain = inferPureDomain(top);
		boolean autoSwitched = false;
		List<String> autoActivated = List.of();
		ToolDomain effectiveSpecialist = specialist;
		if (pureDomain != null && pureDomain != specialist) {
			applySpecialistSwitch(run, pureDomain);
			autoSwitched = true;
			effectiveSpecialist = pureDomain;
			List<String> keys = top.stream()
				.filter(entry -> entry.domain() == pureDomain || entry.domain() == ToolDomain.SHARED)
				.map(entry -> entry.tool().key())
				.toList();
			autoActivated = activateKeysInternal(run, catalog, pureDomain, keys);
		}
		else if (pureDomain != null && pureDomain == specialist) {
			// Already on the right specialist — still activate the hits so schemas appear next turn.
			List<String> keys = top.stream()
				.filter(entry -> entry.domain() == pureDomain || entry.domain() == ToolDomain.SHARED)
				.map(entry -> entry.tool().key())
				.toList();
			autoActivated = activateKeysInternal(run, catalog, specialist, keys);
		}

		List<Map<String, Object>> cards = new ArrayList<>();
		for (ScoredTool entry : top) {
			Map<String, Object> card = new LinkedHashMap<>();
			card.put("key", entry.tool().key());
			card.put("description", truncate(entry.tool().description(), 180));
			card.put("type", entry.tool().type() == null ? null : entry.tool().type().name());
			card.put("sideEffectLevel", entry.level());
			card.put("domain", entry.domain().name());
			if (entry.domain() != ToolDomain.SHARED && entry.domain() != effectiveSpecialist) {
				card.put("requiresAgent", entry.domain() == ToolDomain.API ? AGENT_USE_API : AGENT_USE_BROWSER);
			}
			else {
				card.put("ready", true);
			}
			cards.add(card);
		}
		try {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("tools", cards);
			body.put("count", cards.size());
			body.put("specialist", effectiveSpecialist.name());
			body.put("autoSwitched", autoSwitched);
			body.put("autoActivated", autoActivated);
			if (autoSwitched) {
				body.put("guidance", "Harness auto-switched to " + effectiveSpecialist.name()
					+ " and activated matching tools. Call them on the next step (API multi-calls run in parallel).");
			}
			else if (!autoActivated.isEmpty()) {
				body.put("guidance", "Activated " + autoActivated.size()
					+ " matching tool(s) for the active specialist. Schemas are available next turn.");
			}
			String summary = "Found " + cards.size() + " tools";
			if (autoSwitched) {
				summary += "; auto-switched to " + effectiveSpecialist.name()
					+ " and activated " + autoActivated.size();
			}
			else if (!autoActivated.isEmpty()) {
				summary += "; activated " + autoActivated.size();
			}
			return new ToolExecutionResult(true, objectMapperWrite(body), summary, null);
		}
		catch (Exception ex) {
			return new ToolExecutionResult(false, null, "tool.search failed", ex.getMessage());
		}
	}

	/**
	 * When every scored non-SHARED tool shares one domain (BROWSER or API), that domain is pure and
	 * safe to auto-route. Mixed or empty → no automatic switch.
	 */
	static ToolDomain inferPureDomain(List<ScoredTool> hits) {
		ToolDomain pure = null;
		for (ScoredTool entry : hits) {
			if (entry.domain() == ToolDomain.SHARED) {
				continue;
			}
			if (entry.domain() != ToolDomain.BROWSER && entry.domain() != ToolDomain.API) {
				continue;
			}
			if (pure == null) {
				pure = entry.domain();
			}
			else if (pure != entry.domain()) {
				return null;
			}
		}
		return pure;
	}

	@SuppressWarnings("unchecked")
	private ToolExecutionResult activate(RunEntity run, List<ToolDescriptor> catalog, Map<String, Object> arguments) {
		Object keysObj = arguments == null ? null : arguments.get("keys");
		if (!(keysObj instanceof List<?> keys) || keys.isEmpty()) {
			return new ToolExecutionResult(false, null, "keys required", "keys required");
		}
		List<String> requested = new ArrayList<>();
		for (Object keyObj : keys) {
			if (keyObj != null) {
				requested.add(String.valueOf(keyObj));
			}
		}
		Map<String, ToolDescriptor> byKey = index(catalog);
		List<String> missing = new ArrayList<>();
		List<ToolDescriptor> found = new ArrayList<>();
		for (String key : requested) {
			ToolDescriptor tool = byKey.get(key);
			if (tool == null || isMetaOrAgentSwitchTool(key)) {
				missing.add(key);
				continue;
			}
			found.add(tool);
		}

		ToolDomain before = currentSpecialist(run);
		ToolDomain target = inferPureDomainFromTools(found);
		boolean autoSwitched = false;
		ToolDomain specialist = before;
		// Auto-switch when every found key is one specialist domain (or SHARED + that domain).
		if (target != null && target != before) {
			applySpecialistSwitch(run, target);
			autoSwitched = true;
			specialist = target;
		}

		List<String> activated = activateKeysInternal(run, catalog, specialist,
			found.stream().map(ToolDescriptor::key).toList());
		// Keys still on the other domain after a mixed activate stay blocked.
		List<String> stillWrong = new ArrayList<>();
		for (ToolDescriptor tool : found) {
			ToolDomain domain = ToolCatalogPolicies.domainOf(tool);
			if (domain != ToolDomain.SHARED && domain != specialist && !activated.contains(tool.key())) {
				stillWrong.add(tool.key());
			}
		}
		try {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("activated", activated);
			body.put("activeCount", readActiveKeys(run.getId()).size());
			body.put("specialist", specialist.name());
			body.put("autoSwitched", autoSwitched);
			if (autoSwitched) {
				body.put("previousSpecialist", before.name());
				body.put("guidance", "Harness auto-switched to " + specialist.name()
					+ " so these tools can be used. Call them next (API multi-calls run in parallel).");
			}
			if (!stillWrong.isEmpty()) {
				body.put("wrongDomain", stillWrong);
				body.put("hint", "Mixed browser+API keys in one activate. Activated what matches "
					+ specialist.name() + "; re-activate the others after they are pure-domain.");
			}
			if (!missing.isEmpty()) {
				body.put("missing", missing);
			}
			String summary = "Activated " + activated.size() + " tools";
			if (autoSwitched) {
				summary += " after auto-switch to " + specialist.name();
			}
			if (!stillWrong.isEmpty()) {
				summary += " (" + stillWrong.size() + " still wrong domain)";
			}
			return new ToolExecutionResult(true, objectMapperWrite(body), summary, null);
		}
		catch (Exception ex) {
			return new ToolExecutionResult(false, null, "tool.activate failed", ex.getMessage());
		}
	}

	private static ToolDomain inferPureDomainFromTools(List<ToolDescriptor> tools) {
		List<ScoredTool> fake = new ArrayList<>();
		for (ToolDescriptor tool : tools) {
			fake.add(new ScoredTool(tool, "", ToolCatalogPolicies.domainOf(tool), 1));
		}
		return inferPureDomain(fake);
	}

	/**
	 * Activate keys that belong to {@code specialist} (or SHARED). Returns keys newly or already
	 * present in the active set after the write.
	 */
	private List<String> activateKeysInternal(RunEntity run, List<ToolDescriptor> catalog, ToolDomain specialist,
		List<String> keys) {
		if (keys == null || keys.isEmpty()) {
			return List.of();
		}
		Map<String, ToolDescriptor> byKey = index(catalog);
		Set<String> active = new LinkedHashSet<>(readActiveKeys(run.getId()));
		List<String> activated = new ArrayList<>();
		for (String key : keys) {
			if (key == null || key.isBlank()) {
				continue;
			}
			ToolDescriptor tool = byKey.get(key);
			if (tool == null || isMetaOrAgentSwitchTool(key)) {
				continue;
			}
			ToolDomain domain = ToolCatalogPolicies.domainOf(tool);
			if (domain != ToolDomain.SHARED && domain != specialist) {
				continue;
			}
			if (active.size() >= maxActivatedPerRun && !active.contains(key)) {
				break;
			}
			if (active.add(key) || active.contains(key)) {
				if (!activated.contains(key)) {
					activated.add(key);
				}
			}
		}
		for (ToolDescriptor meta : metaToolDescriptors()) {
			active.add(meta.key());
		}
		writeActiveKeys(run, active);
		return activated;
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

	private static boolean isAlwaysOnFor(ToolDescriptor tool, ToolDomain specialist) {
		if (isMetaOrAgentSwitchTool(tool.key())) {
			return true;
		}
		if ("knowledge.search".equals(tool.key())) {
			return true;
		}
		if (specialist == ToolDomain.BROWSER) {
			return ToolCatalogPolicies.domainOf(tool) == ToolDomain.BROWSER
				&& (tool.type() == ToolType.BUILD_IN || tool.type() == ToolType.CLIENT);
		}
		return false;
	}

	private static Map<String, ToolDescriptor> index(List<ToolDescriptor> catalog) {
		Map<String, ToolDescriptor> byKey = new LinkedHashMap<>();
		for (ToolDescriptor tool : catalog) {
			byKey.put(tool.key(), tool);
		}
		return byKey;
	}

	private List<String> readActiveKeys(String runId) {
		if (runId == null || runId.isBlank()) {
			return List.of();
		}
		return activeKeysFrom(runMemoryService.getSnapshot(runId));
	}

	/** Pure read of the active-key list from an already-loaded snapshot — issues no query. */
	private List<String> activeKeysFrom(RunMemoryService.RunMemorySnapshot snapshot) {
		if (snapshot == null) {
			return List.of();
		}
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
		if (run == null || run.getId() == null || run.getId().isBlank()) {
			return;
		}
		try {
			runMemoryService.mergeSummary(run, Map.of(ACTIVE_KEYS_MEMORY, new ArrayList<>(active)));
		}
		catch (Exception ignored) {
			// Memory merge is best-effort; planning can continue with the in-memory set.
		}
	}

	private void writeSpecialist(RunEntity run, ToolDomain specialist) {
		if (run == null || run.getId() == null || run.getId().isBlank()) {
			return;
		}
		try {
			runMemoryService.mergeSummary(run, Map.of(SPECIALIST_MEMORY, specialist.name()));
		}
		catch (Exception ignored) {
			// Best-effort.
		}
	}

	/** Delegates to {@link ToolContract} so search filters and policy never classify a tool differently. */
	private static String sideEffectLevel(ToolDescriptor tool) {
		return ToolContract.inferSideEffectLevel(tool).name();
	}

	private record ScoredTool(ToolDescriptor tool, String level, ToolDomain domain, double score) {
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
		return raw / queryTerms.size() + 0.01 / Math.max(1, keyTerms.size());
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
