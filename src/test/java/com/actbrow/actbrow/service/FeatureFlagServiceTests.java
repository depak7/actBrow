package com.actbrow.actbrow.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FeatureFlagServiceTests {

	private final FeatureFlagService flags = new FeatureFlagService(true, false);

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

	@Test
	void configuredKillSwitchSurvivesRestart() {
		// A restart re-runs the constructor: an operator who set ACTBROW_TOOLS_ENABLED=false must
		// not have tools silently re-enabled by the next deploy.
		FeatureFlagService restarted = new FeatureFlagService(false, true);
		assertThat(restarted.isEnabled("a1", FeatureFlagService.TOOLS_ENABLED)).isFalse();
		assertThat(restarted.isEnabled("a1", FeatureFlagService.SHADOW_MODE)).isTrue();
	}

	@Test
	void perAssistantOverrideStillAppliesOverConfiguredBaseline() {
		FeatureFlagService restricted = new FeatureFlagService(false, false);
		restricted.setAssistantFlag("a1", FeatureFlagService.TOOLS_ENABLED, true);
		assertThat(restricted.isEnabled("a1", FeatureFlagService.TOOLS_ENABLED)).isTrue();
		assertThat(restricted.isEnabled("a2", FeatureFlagService.TOOLS_ENABLED)).isFalse();
	}
}
