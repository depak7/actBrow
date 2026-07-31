package com.actbrow.actbrow.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Per-assistant feature flags (Phase 7). Lets new customers launch with restricted capabilities and
 * lets risky behavior be toggled (e.g. shadow/observe-only mode for write actions). Runtime
 * overrides are in-memory; an assistant with no explicit setting falls back to the global default.
 *
 * <p>Global defaults are seeded from configuration ({@code actbrow.flags.*}) rather than hardcoded,
 * so an operator response to an incident survives a restart. Runtime overrides set via
 * {@link #setAssistantFlag} and {@link #setGlobalDefault} do NOT survive a restart — after a deploy
 * the process reverts to the configured baseline. To durably disable tools, set
 * {@code ACTBROW_TOOLS_ENABLED=false} (or {@code ACTBROW_SHADOW_MODE=true}) in the environment.
 */
@Service
public class FeatureFlagService {

	private static final Logger log = LoggerFactory.getLogger(FeatureFlagService.class);

	/** When true, write/destructive tools are observed and recorded but not actually executed. */
	public static final String SHADOW_MODE = "shadow_mode";

	/** When false, all tool execution is disabled for the assistant (hard kill switch). */
	public static final String TOOLS_ENABLED = "tools_enabled";

	private final Map<String, Boolean> globalDefaults = new ConcurrentHashMap<>();

	private final Map<String, Map<String, Boolean>> perAssistant = new ConcurrentHashMap<>();

	public FeatureFlagService(
		@Value("${actbrow.flags.tools-enabled:true}") boolean toolsEnabledDefault,
		@Value("${actbrow.flags.shadow-mode:false}") boolean shadowModeDefault) {
		globalDefaults.put(TOOLS_ENABLED, toolsEnabledDefault);
		globalDefaults.put(SHADOW_MODE, shadowModeDefault);
		// Log the safety-relevant baseline at startup so a restart that changes effective behavior
		// is visible in the logs rather than silent.
		if (!toolsEnabledDefault || shadowModeDefault) {
			log.warn("Tool execution baseline is restricted by configuration: tools_enabled={}, shadow_mode={}",
				toolsEnabledDefault, shadowModeDefault);
		}
	}

	public boolean isEnabled(String assistantId, String flag) {
		Map<String, Boolean> flags = assistantId == null ? null : perAssistant.get(assistantId);
		if (flags != null && flags.containsKey(flag)) {
			return flags.get(flag);
		}
		return globalDefaults.getOrDefault(flag, false);
	}

	public void setAssistantFlag(String assistantId, String flag, boolean enabled) {
		perAssistant.computeIfAbsent(assistantId, k -> new ConcurrentHashMap<>()).put(flag, enabled);
	}

	public void setGlobalDefault(String flag, boolean enabled) {
		globalDefaults.put(flag, enabled);
	}
}
