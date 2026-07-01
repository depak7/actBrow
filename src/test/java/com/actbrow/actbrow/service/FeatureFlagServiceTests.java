package com.actbrow.actbrow.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FeatureFlagServiceTests {

	private final FeatureFlagService flags = new FeatureFlagService();

	@Test
	void defaultsToolsEnabledAndShadowOff() {
		assertThat(flags.isEnabled("a1", FeatureFlagService.TOOLS_ENABLED)).isTrue();
		assertThat(flags.isEnabled("a1", FeatureFlagService.SHADOW_MODE)).isFalse();
	}

	@Test
	void perAssistantOverridesGlobal() {
		flags.setAssistantFlag("a1", FeatureFlagService.SHADOW_MODE, true);
		assertThat(flags.isEnabled("a1", FeatureFlagService.SHADOW_MODE)).isTrue();
		// A different assistant still gets the global default.
		assertThat(flags.isEnabled("a2", FeatureFlagService.SHADOW_MODE)).isFalse();
	}

	@Test
	void globalDefaultCanBeChanged() {
		flags.setGlobalDefault(FeatureFlagService.TOOLS_ENABLED, false);
		assertThat(flags.isEnabled("a3", FeatureFlagService.TOOLS_ENABLED)).isFalse();
	}

	@Test
	void unknownFlagIsFalse() {
		assertThat(flags.isEnabled("a1", "no_such_flag")).isFalse();
	}
}
