package com.actbrow.actbrow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.actbrow.actbrow.agent.ToolDescriptor;
import com.actbrow.actbrow.agent.ToolExecutionResult;
import com.actbrow.actbrow.model.RunEntity;
import com.actbrow.actbrow.model.ToolType;
import com.fasterxml.jackson.databind.ObjectMapper;

class ToolDomainAndSpecialistTests {

	@Mock
	private RunMemoryService runMemoryService;

	private ProgressiveToolDisclosureService service;
	private final ObjectMapper objectMapper = new ObjectMapper();
	private final AtomicReference<Map<String, Object>> summary = new AtomicReference<>(Map.of());

	@BeforeEach
	void setUp() {
		MockitoAnnotations.openMocks(this);
		summary.set(Map.of());
		when(runMemoryService.getSnapshot(anyString()))
			.thenAnswer(inv -> new RunMemoryService.RunMemorySnapshot("", "", "", Map.of(), Map.of(), List.of(),
				null, summary.get()));
		org.mockito.Mockito.doAnswer(inv -> {
			@SuppressWarnings("unchecked")
			Map<String, Object> patch = inv.getArgument(1);
			Map<String, Object> next = new java.util.LinkedHashMap<>(summary.get());
			next.putAll(patch);
			summary.set(next);
			return null;
		}).when(runMemoryService).mergeSummary(any(), any());
		service = new ProgressiveToolDisclosureService(runMemoryService, objectMapper, 25, 40);
	}

	@Test
	void domainOfSplitsBrowserAndApiTools() {
		assertThat(ToolCatalogPolicies.domainOf(browser("app.navigate"))).isEqualTo(ToolDomain.BROWSER);
		assertThat(ToolCatalogPolicies.domainOf(browser("page.observe"))).isEqualTo(ToolDomain.BROWSER);
		assertThat(ToolCatalogPolicies.domainOf(api("crm.ticket.create"))).isEqualTo(ToolDomain.API);
		assertThat(ToolCatalogPolicies.domainOf(mcp("mcp.orders.list"))).isEqualTo(ToolDomain.API);
		assertThat(ToolCatalogPolicies.domainOf(knowledge())).isEqualTo(ToolDomain.SHARED);
	}

	@Test
	void parallelSafeOnlyForServerSideTools() {
		assertThat(ToolCatalogPolicies.isParallelSafe(api("crm.ticket.create"))).isTrue();
		assertThat(ToolCatalogPolicies.isParallelSafe(mcp("mcp.x"))).isTrue();
		assertThat(ToolCatalogPolicies.isParallelSafe(browser("app.navigate"))).isFalse();
		assertThat(ToolCatalogPolicies.isParallelSafe(browser("page.observe"))).isFalse();
		assertThat(ToolCatalogPolicies.isParallelSafe(
			new ToolDescriptor(null, ProgressiveToolDisclosureService.TOOL_SEARCH, "s", "{}", ToolType.BUILD_IN,
				ProgressiveToolDisclosureService.TOOL_SEARCH, Map.of(), Map.of()))).isFalse();
		ToolDescriptor browserHttp = new ToolDescriptor("id", "proxy.get", "desc", "{}", ToolType.SERVER_HTTP,
			"proxy.get", Map.of(), Map.of("execution", "browser"));
		assertThat(ToolCatalogPolicies.isParallelSafe(browserHttp)).isFalse();
	}

	@Test
	void browserSpecialistDoesNotExposeApiTools() {
		RunEntity run = run("r1");
		List<ToolDescriptor> catalog = List.of(
			browser("app.navigate"),
			browser("page.observe"),
			api("orders.list"),
			api("orders.refund"));

		List<String> keys = service.selectForPlanning(run, catalog).stream().map(ToolDescriptor::key).toList();

		assertThat(keys).contains("app.navigate", "page.observe");
		assertThat(keys).contains(ProgressiveToolDisclosureService.AGENT_USE_API);
		assertThat(keys).doesNotContain("orders.list", "orders.refund");
	}

	@Test
	void apiSpecialistExposesAddedApiToolsAndHidesBrowser() {
		RunEntity run = run("r1");
		List<ToolDescriptor> catalog = List.of(
			browser("app.navigate"),
			browser("page.observe"),
			api("orders.list"),
			api("orders.refund"),
			api("billing.invoice.get"));

		service.execute(run, catalog, ProgressiveToolDisclosureService.AGENT_USE_API, Map.of());
		List<String> keys = service.selectForPlanning(run, catalog).stream().map(ToolDescriptor::key).toList();

		assertThat(keys).contains("orders.list", "orders.refund", "billing.invoice.get");
		assertThat(keys).contains(ProgressiveToolDisclosureService.AGENT_USE_BROWSER);
		assertThat(keys).doesNotContain("app.navigate", "page.observe");
	}

	@Test
	void searchWithOnlyApiHitsAutoSwitchesAndActivates() throws Exception {
		RunEntity run = run("r1");
		List<ToolDescriptor> catalog = List.of(
			browser("app.navigate"),
			api("orders.list"),
			api("orders.refund.create"));

		ToolExecutionResult result = service.execute(run, catalog, ProgressiveToolDisclosureService.TOOL_SEARCH,
			Map.of("query", "refund order"));

		assertThat(result.success()).isTrue();
		assertThat(result.textSummary()).containsIgnoringCase("auto-switched");
		@SuppressWarnings("unchecked")
		Map<String, Object> body = objectMapper.readValue(result.structuredOutput(), Map.class);
		assertThat(body.get("autoSwitched")).isEqualTo(true);
		assertThat(body.get("specialist")).isEqualTo("API");
		assertThat((List<?>) body.get("autoActivated")).isNotEmpty();

		// Next planning turn must expose API tools, not browser navigate.
		List<String> keys = service.selectForPlanning(run, catalog).stream().map(ToolDescriptor::key).toList();
		assertThat(keys).contains("orders.refund.create");
		assertThat(keys).doesNotContain("app.navigate");
	}

	@Test
	void activateAutoSwitchesWhenKeysArePureApi() throws Exception {
		RunEntity run = run("r1");
		List<ToolDescriptor> catalog = List.of(api("orders.list"), browser("app.navigate"));

		ToolExecutionResult result = service.execute(run, catalog, ProgressiveToolDisclosureService.TOOL_ACTIVATE,
			Map.of("keys", List.of("orders.list")));

		assertThat(result.success()).isTrue();
		@SuppressWarnings("unchecked")
		Map<String, Object> body = objectMapper.readValue(result.structuredOutput(), Map.class);
		assertThat(body.get("autoSwitched")).isEqualTo(true);
		assertThat(body.get("specialist")).isEqualTo("API");
		assertThat(body.get("activated").toString()).contains("orders.list");
		assertThat(result.textSummary()).doesNotContain("blocked");

		List<String> keys = service.selectForPlanning(run, catalog).stream().map(ToolDescriptor::key).toList();
		assertThat(keys).contains("orders.list");
		assertThat(keys).doesNotContain("app.navigate");
	}

	@Test
	void routeForToolCallSwitchesWhenModelCallsOtherDomainDirectly() {
		RunEntity run = run("r1");
		List<ToolDescriptor> catalog = List.of(api("orders.list"), browser("app.navigate"));

		ProgressiveToolDisclosureService.AutoRouteResult route = service.routeForToolCall(run, catalog,
			api("orders.list"));

		assertThat(route.switched()).isTrue();
		assertThat(route.specialistBefore()).isEqualTo(ToolDomain.BROWSER);
		assertThat(route.specialistAfter()).isEqualTo(ToolDomain.API);
		assertThat(route.activated()).contains("orders.list");
	}

	@Test
	void specialistRuntimeGuidanceMentionsActiveAgent() {
		RunEntity run = run("r1");
		service.execute(run, List.of(), ProgressiveToolDisclosureService.AGENT_USE_API, Map.of());
		String guidance = service.specialistRuntimeGuidance(runMemoryService.getSnapshot(run.getId()));
		assertThat(guidance).contains("Active specialist: API");
		assertThat(guidance).contains("parallel");
	}

	@Test
	void activateDoesNotAutoSwitchWhenKeysMixBrowserAndApi() {
		RunEntity run = run("r1");
		ToolDescriptor client = new ToolDescriptor("id-c", "custom.client", "click", "{}", ToolType.CLIENT,
			"custom.client", Map.of(), Map.of());
		List<ToolDescriptor> catalog = List.of(api("orders.list"), client);

		ToolExecutionResult result = service.execute(run, catalog, ProgressiveToolDisclosureService.TOOL_ACTIVATE,
			Map.of("keys", List.of("orders.list", "custom.client")));

		assertThat(result.success()).isTrue();
		// Mixed → no auto-switch (stays BROWSER); only browser-domain key activates.
		assertThat(result.structuredOutput()).contains("\"autoSwitched\":false");
		assertThat(result.structuredOutput()).contains("custom.client");
		assertThat(result.structuredOutput()).contains("wrongDomain");
	}

	private static ToolDescriptor browser(String key) {
		return new ToolDescriptor("id-" + key, key, "browser " + key, "{}", ToolType.BUILD_IN, key, Map.of(),
			Map.of());
	}

	private static ToolDescriptor api(String key) {
		return new ToolDescriptor("id-" + key, key, "api " + key, "{}", ToolType.SERVER_HTTP, key, Map.of(), Map.of());
	}

	private static ToolDescriptor mcp(String key) {
		return new ToolDescriptor("id-" + key, key, "mcp " + key, "{}", ToolType.MCP, key, Map.of(), Map.of());
	}

	private static ToolDescriptor knowledge() {
		return new ToolDescriptor("id-ks", "knowledge.search", "kb", "{}", ToolType.BUILD_IN, "knowledge.search",
			Map.of(), Map.of());
	}

	private static RunEntity run(String id) {
		RunEntity run = new RunEntity();
		run.setId(id);
		run.setAssistantId("a1");
		run.setConversationId("c1");
		return run;
	}
}
