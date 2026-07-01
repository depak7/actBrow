package com.actbrow.actbrow.service;

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
