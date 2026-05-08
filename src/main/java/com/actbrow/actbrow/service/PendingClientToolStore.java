package com.actbrow.actbrow.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.actbrow.actbrow.agent.ToolExecutionResult;

@Component
public class PendingClientToolStore {

	private final Map<String, CompletableFuture<ToolExecutionResult>> pendingResults = new ConcurrentHashMap<>();
	private final Map<String, Set<String>> toolCallIdsByRunId = new ConcurrentHashMap<>();

	public CompletableFuture<ToolExecutionResult> register(String runId, String toolCallId) {
		CompletableFuture<ToolExecutionResult> future = new CompletableFuture<>();
		pendingResults.put(toolCallId, future);
		if (runId != null) {
			toolCallIdsByRunId.computeIfAbsent(runId, k -> ConcurrentHashMap.newKeySet()).add(toolCallId);
		}
		return future;
	}

	public void complete(String toolCallId, ToolExecutionResult result) {
		CompletableFuture<ToolExecutionResult> future = pendingResults.remove(toolCallId);
		if (future == null) {
			return; // already completed or unknown — ignore duplicate submissions on reconnect
		}
		future.complete(result);
	}

	/**
	 * Cancels a pending future. Called when a TimeoutException fires in RunService so the
	 * future does not linger in the map after the run has already failed or been cancelled.
	 */
	public void cancel(String toolCallId) {
		CompletableFuture<ToolExecutionResult> future = pendingResults.remove(toolCallId);
		if (future != null) {
			future.cancel(true);
		}
	}

	/**
	 * Cancels every pending future registered for a run. Used when a conversation is deleted so
	 * in-flight virtual threads unblock immediately instead of timing out on a vanished row.
	 */
	public void cancelByRunId(String runId) {
		Set<String> toolCallIds = toolCallIdsByRunId.remove(runId);
		if (toolCallIds == null) {
			return;
		}
		for (String toolCallId : new ArrayList<>(toolCallIds)) {
			cancel(toolCallId);
		}
	}
}
