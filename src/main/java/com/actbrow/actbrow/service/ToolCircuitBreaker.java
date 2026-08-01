package com.actbrow.actbrow.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Service;

/**
 * Per-tool circuit breaker (Phase 7). After too many consecutive failures a tool's circuit opens
 * and calls are short-circuited for a cooldown window, then allowed again in a half-open probe.
 * Isolates a failing tool without taking down the whole agent. In-memory, keyed by tool.
 */
@Service
public class ToolCircuitBreaker {

	private static final int FAILURE_THRESHOLD = 5;
	private static final long COOLDOWN_MS = 30_000;

	private enum State { CLOSED, OPEN, HALF_OPEN }

	private final ConcurrentHashMap<String, Circuit> circuits = new ConcurrentHashMap<>();

	/** @return true if a call to this tool is currently permitted. */
	public boolean allow(String toolKey) {
		Circuit circuit = circuits.computeIfAbsent(toolKey, k -> new Circuit());
		return circuit.allow();
	}

	public void recordSuccess(String toolKey) {
		Circuit circuit = circuits.get(toolKey);
		if (circuit != null) {
			circuit.onSuccess();
		}
	}

	public void recordFailure(String toolKey) {
		circuits.computeIfAbsent(toolKey, k -> new Circuit()).onFailure();
	}

	public boolean isOpen(String toolKey) {
		Circuit circuit = circuits.get(toolKey);
		return circuit != null && !circuit.allow();
	}

	/**
	 * Read-only view of every circuit belonging to one assistant, for operator dashboards.
	 *
	 * <p>Callers key circuits as {@code assistantId + "|" + toolKey} (see {@code RunService}), so the
	 * prefix is matched and then stripped — the caller already knows the assistant and wants bare tool
	 * keys. Unlike {@link #isOpen} this never transitions a circuit into half-open: inspecting the
	 * dashboard must not consume the probe that a real call is entitled to.
	 *
	 * @return tool key -> whether its circuit is currently open (calls short-circuited).
	 */
	public Map<String, Boolean> snapshotFor(String assistantId) {
		if (assistantId == null || assistantId.isBlank()) {
			return Map.of();
		}
		String prefix = assistantId + "|";
		Map<String, Boolean> snapshot = new LinkedHashMap<>();
		// Iterating a ConcurrentHashMap is weakly consistent, which is fine — this is a point-in-time
		// operator view, not a value anything branches on.
		circuits.forEach((key, circuit) -> {
			if (key.startsWith(prefix)) {
				snapshot.put(key.substring(prefix.length()), circuit.openNow());
			}
		});
		return snapshot;
	}

	/**
	 * Force a circuit closed. Used by the operator safety endpoint after the underlying tool has been
	 * fixed, so recovery does not have to wait out the cooldown window. Unknown keys are a no-op
	 * rather than an error: a circuit that was never tripped is already closed, which is what the
	 * caller asked for.
	 */
	public void reset(String toolKey) {
		Circuit circuit = circuits.get(toolKey);
		if (circuit != null) {
			circuit.onSuccess();
		}
	}

	private static final class Circuit {
		private volatile State state = State.CLOSED;
		private final AtomicInteger consecutiveFailures = new AtomicInteger();
		private final AtomicLong openedAtMs = new AtomicLong();

		synchronized boolean allow() {
			if (state == State.OPEN) {
				if (System.currentTimeMillis() - openedAtMs.get() >= COOLDOWN_MS) {
					state = State.HALF_OPEN;
					return true;
				}
				return false;
			}
			return true;
		}

		/** Non-mutating counterpart to {@link #allow()} — reports state without arming a probe. */
		synchronized boolean openNow() {
			return state == State.OPEN && System.currentTimeMillis() - openedAtMs.get() < COOLDOWN_MS;
		}

		synchronized void onSuccess() {
			consecutiveFailures.set(0);
			state = State.CLOSED;
		}

		synchronized void onFailure() {
			int failures = consecutiveFailures.incrementAndGet();
			if (state == State.HALF_OPEN || failures >= FAILURE_THRESHOLD) {
				state = State.OPEN;
				openedAtMs.set(System.currentTimeMillis());
			}
		}
	}
}
