package com.actbrow.actbrow.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.actbrow.actbrow.model.AssistantSafetyFlagEntity;
import com.actbrow.actbrow.repository.AssistantSafetyFlagRepository;

/**
 * Per-assistant feature flags (Phase 7). Lets new customers launch with restricted capabilities and
 * lets risky behavior be toggled (e.g. shadow/observe-only mode for write actions). An assistant with
 * no explicit setting falls back to the global default.
 *
 * <p>Global defaults are seeded from configuration ({@code actbrow.flags.*}), so an operator response
 * set in the environment ({@code ACTBROW_TOOLS_ENABLED=false}) holds for every assistant across
 * restarts.
 *
 * <p>Per-assistant overrides set from the dashboard are persisted to {@code assistant_safety_flags}.
 * They previously lived only in process memory, so a restart or deploy silently turned a flipped
 * kill switch back on. Reads go through a short cache ({@link #CACHE_TTL_MS}) because
 * {@link #isEnabled} runs before every tool call; the TTL also bounds how long another instance can
 * keep serving a stale value after an operator flips a switch.
 */
@Service
public class FeatureFlagService {

	private static final Logger log = LoggerFactory.getLogger(FeatureFlagService.class);

	/** When true, write/destructive tools are observed and recorded but not actually executed. */
	public static final String SHADOW_MODE = "shadow_mode";

	/** When false, all tool execution is disabled for the assistant (hard kill switch). */
	public static final String TOOLS_ENABLED = "tools_enabled";

	/** How long a per-assistant read is trusted before it is re-read from the database. */
	static final long CACHE_TTL_MS = 5_000;

	private final Map<String, Boolean> globalDefaults = new ConcurrentHashMap<>();

	private final Map<String, Map<String, Boolean>> perAssistant = new ConcurrentHashMap<>();

	private final Map<String, Long> loadedAtNanos = new ConcurrentHashMap<>();

	/** Null only for the in-memory constructor used by unit tests and tooling without a database. */
	private final AssistantSafetyFlagRepository repository;

	private final LongSupplier nanoClock;

	@Autowired
	public FeatureFlagService(
		@Value("${actbrow.flags.tools-enabled:true}") boolean toolsEnabledDefault,
		@Value("${actbrow.flags.shadow-mode:false}") boolean shadowModeDefault,
		AssistantSafetyFlagRepository repository) {
		this(toolsEnabledDefault, shadowModeDefault, repository, System::nanoTime);
	}

	/** In-memory only: overrides are not persisted. For tests and tooling with no database. */
	public FeatureFlagService(boolean toolsEnabledDefault, boolean shadowModeDefault) {
		this(toolsEnabledDefault, shadowModeDefault, null, System::nanoTime);
	}

	FeatureFlagService(boolean toolsEnabledDefault, boolean shadowModeDefault,
		AssistantSafetyFlagRepository repository, LongSupplier nanoClock) {
		this.repository = repository;
		this.nanoClock = nanoClock;
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
		if (assistantId != null) {
			refreshIfStale(assistantId);
		}
		Map<String, Boolean> flags = assistantId == null ? null : perAssistant.get(assistantId);
		if (flags != null && flags.containsKey(flag)) {
			return flags.get(flag);
		}
		return globalDefaults.getOrDefault(flag, false);
	}

	public void setAssistantFlag(String assistantId, String flag, boolean enabled) {
		setAssistantFlag(assistantId, flag, enabled, null);
	}

	/**
	 * Persists the override before applying it. A failed write propagates to the caller on purpose: an
	 * operator must never see "tools disabled" while the next restart would bring them back.
	 */
	public void setAssistantFlag(String assistantId, String flag, boolean enabled, String updatedBy) {
		if (repository != null) {
			persist(assistantId, flag, enabled, updatedBy);
		}
		perAssistant.computeIfAbsent(assistantId, k -> new ConcurrentHashMap<>()).put(flag, enabled);
		if (repository != null) {
			// Re-read on next access so this instance sees every persisted flag, not just this one.
			loadedAtNanos.remove(assistantId);
		}
	}

	public void setGlobalDefault(String flag, boolean enabled) {
		globalDefaults.put(flag, enabled);
	}

	private void persist(String assistantId, String flag, boolean enabled, String updatedBy) {
		try {
			upsert(assistantId, flag, enabled, updatedBy);
		}
		catch (DataIntegrityViolationException raced) {
			// Two operators flipped the same switch at once and both tried to insert; the row exists
			// now, so the retry takes the update path.
			upsert(assistantId, flag, enabled, updatedBy);
		}
	}

	private void upsert(String assistantId, String flag, boolean enabled, String updatedBy) {
		AssistantSafetyFlagEntity row = repository.findByAssistantIdAndFlag(assistantId, flag)
			.orElseGet(AssistantSafetyFlagEntity::new);
		row.setAssistantId(assistantId);
		row.setFlag(flag);
		row.setEnabled(enabled);
		row.setUpdatedBy(updatedBy);
		repository.save(row);
	}

	private void refreshIfStale(String assistantId) {
		if (repository == null) {
			return;
		}
		long now = nanoClock.getAsLong();
		Long loadedAt = loadedAtNanos.get(assistantId);
		if (loadedAt != null && now - loadedAt < TimeUnit.MILLISECONDS.toNanos(CACHE_TTL_MS)) {
			return;
		}
		try {
			Map<String, Boolean> fresh = new ConcurrentHashMap<>();
			for (AssistantSafetyFlagEntity row : repository.findAllByAssistantId(assistantId)) {
				fresh.put(row.getFlag(), row.isEnabled());
			}
			perAssistant.put(assistantId, fresh);
		}
		catch (RuntimeException e) {
			// Keep serving the last known value rather than dropping to the baseline: failing open on a
			// kill switch because the database blipped would be the worst possible outcome.
			log.warn("Could not refresh safety flags for assistant {}; keeping last known values", assistantId, e);
		}
		loadedAtNanos.put(assistantId, now);
	}
}
