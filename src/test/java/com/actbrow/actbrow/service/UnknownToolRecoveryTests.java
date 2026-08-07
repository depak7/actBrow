package com.actbrow.actbrow.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.actbrow.actbrow.agent.ToolCall;
import com.actbrow.actbrow.agent.ToolDescriptor;
import com.actbrow.actbrow.agent.ToolExecutionResult;
import com.actbrow.actbrow.model.FailureType;
import com.actbrow.actbrow.model.ToolType;

class UnknownToolRecoveryTests {

	@Test
	void resolveToolFindsCatalogToolByWireFormEvenWhenNotActive() {
		ToolDescriptor navigate = descriptor("id-nav", "app.navigate");
		ToolDescriptor observe = descriptor("id-obs", "page.observe");
		// Active set only has observe; model still called app_navigate from a prior turn.
		List<ToolDescriptor> active = List.of(observe);
		List<ToolDescriptor> catalog = List.of(navigate, observe);
		ToolCall call = new ToolCall("c1", null, "app_navigate", Map.of("path", "/orders"));

		Optional<ToolDescriptor> resolved = RunService.resolveTool(active, catalog, call);

		assertThat(resolved).isPresent();
		assertThat(resolved.get().key()).isEqualTo("app.navigate");
	}

	@Test
	void resolveToolEmptyForInventedName() {
		ToolDescriptor navigate = descriptor("id-nav", "app.navigate");
		ToolCall call = new ToolCall("c1", null, "browser_click", Map.of("selector", "#x"));

		Optional<ToolDescriptor> resolved = RunService.resolveTool(List.of(navigate), List.of(navigate), call);

		assertThat(resolved).isEmpty();
	}

	@Test
	void syntheticUnknownToolListsAvailableToolsAndIsClassifiedAsExhausted() {
		List<ToolDescriptor> available = List.of(
			descriptor("1", "app.navigate"),
			descriptor("2", "page.observe"));

		ToolExecutionResult result = RunService.syntheticUnknownTool("browser_click", available);

		assertThat(result.success()).isFalse();
		assertThat(result.error()).containsIgnoringCase("unknown tool");
		assertThat(result.textSummary()).contains("browser_click");
		assertThat(result.textSummary()).contains("app.navigate");
		assertThat(result.textSummary()).contains("page.observe");
		assertThat(result.textSummary()).contains("tool.search");

		FailureClassifier classifier = new FailureClassifier();
		assertThat(classifier.classify(null, result)).isEqualTo(FailureType.TOOL_EXHAUSTED);
	}

	@Test
	void normalizeToolWireNameUnifiesDotsDashesAndUnderscores() {
		assertThat(RunService.normalizeToolWireName("app.navigate"))
			.isEqualTo(RunService.normalizeToolWireName("app_navigate"));
		assertThat(RunService.normalizeToolWireName("app-navigate"))
			.isEqualTo(RunService.normalizeToolWireName("app.navigate"));
	}

	private static ToolDescriptor descriptor(String id, String key) {
		return new ToolDescriptor(id, key, "desc", "{\"type\":\"object\"}", ToolType.BUILD_IN, key, Map.of(),
			Map.of());
	}
}
