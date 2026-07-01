package com.actbrow.actbrow.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

/**
 * Per-assistant feature flags (Phase 7). Lets new customers launch with restricted capabilities and
 * lets risky behavior be toggled (e.g. shadow/observe-only mode for write actions). In-memory and
 * mutable at runtime; falls back to a global default when an assistant has no explicit setting.
 */
@Service
public class FeatureFlagService {

	/** When true, write/destructive tools are observed and recorded but not actually executed. */
	public static final String SHADOW_MODE = "shadow_mode";

	/** When false, all tool execution is disabled for the assistant (hard kill switch). */
	public static final String TOOLS_ENABLED = "tools_enabled";

	private final Map<String, Boolean> globalDefaults = new ConcurrentHashMap<>(Map.of(
		SHADOW_MODE, false,
		TOOLS_ENABLED, true));

	private final Map<String, Map<String, Boolean>> perAssistant = new ConcurrentHashMap<>();

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
