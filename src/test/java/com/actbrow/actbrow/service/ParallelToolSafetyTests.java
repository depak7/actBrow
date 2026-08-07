package com.actbrow.actbrow.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.actbrow.actbrow.agent.ToolDescriptor;
import com.actbrow.actbrow.model.ToolType;

/**
 * Guards the parallel-execution partition: only server-side API tools may share virtual threads.
 * Client/browser tools flip run status and must stay on the run-loop thread.
 */
class ParallelToolSafetyTests {

	@Test
	void twoHttpToolsAreParallelSafe() {
		assertThat(ToolCatalogPolicies.isParallelSafe(http("a"))).isTrue();
		assertThat(ToolCatalogPolicies.isParallelSafe(http("b"))).isTrue();
	}

	@Test
	void navigateIsNeverParallelSafe() {
		ToolDescriptor navigate = new ToolDescriptor("1", "app.navigate", "nav", "{}", ToolType.BUILD_IN,
			"app.navigate", Map.of(), Map.of());
		assertThat(ToolCatalogPolicies.isParallelSafe(navigate)).isFalse();
	}

	@Test
	void clientTypeIsNeverParallelSafe() {
		ToolDescriptor client = new ToolDescriptor("1", "custom.click", "click", "{}", ToolType.CLIENT,
			"custom.click", Map.of(), Map.of());
		assertThat(ToolCatalogPolicies.isParallelSafe(client)).isFalse();
	}

	private static ToolDescriptor http(String key) {
		return new ToolDescriptor("id-" + key, key, "desc", "{}", ToolType.SERVER_HTTP, key, Map.of(), Map.of());
	}
}
