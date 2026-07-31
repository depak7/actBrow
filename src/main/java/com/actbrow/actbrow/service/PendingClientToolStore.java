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
	private final Map<String, String> runIdByToolCallId = new ConcurrentHashMap<>();
	private final Map<String, Set<String>> toolCallIdsByRunId = new ConcurrentHashMap<>();

	public CompletableFuture<ToolExecutionResult> register(String runId, String toolCallId) {
		if (runId == null || runId.isBlank() || toolCallId == null || toolCallId.isBlank()) {
			throw new IllegalArgumentException("runId and toolCallId are required");
		}
		String existingRun = runIdByToolCallId.putIfAbsent(toolCallId, runId);
		if (existingRun != null && !existingRun.equals(runId)) {
			throw new IllegalArgumentException("toolCallId is already bound to another run");
		}
		if (existingRun != null) {
			throw new IllegalArgumentException("Duplicate pending tool registration");
		}
		CompletableFuture<ToolExecutionResult> future = new CompletableFuture<>();
		CompletableFuture<ToolExecutionResult> previous = pendingResults.putIfAbsent(toolCallId, future);
		if (previous != null) {
			runIdByToolCallId.remove(toolCallId, runId);
			throw new IllegalArgumentException("Duplicate pending tool registration");
		}
		toolCallIdsByRunId.computeIfAbsent(runId, k -> ConcurrentHashMap.newKeySet()).add(toolCallId);
		return future;
	}

	public void complete(String runId, String toolCallId, ToolExecutionResult result) {
		String boundRunId = runIdByToolCallId.get(toolCallId);
		if (boundRunId == null || !boundRunId.equals(runId)) {
			throw new IllegalArgumentException("toolCallId is not pending for this run");
		}
		CompletableFuture<ToolExecutionResult> future = pendingResults.remove(toolCallId);
		runIdByToolCallId.remove(toolCallId);
		Set<String> ids = toolCallIdsByRunId.get(runId);
		if (ids != null) {
			ids.remove(toolCallId);
			if (ids.isEmpty()) {
				toolCallIdsByRunId.remove(runId);
			}
		}
		if (future == null) {
			return;
		}
		future.complete(result);
	}

	/**
	 * Cancels a pending future. Called when a TimeoutException fires in RunService so the
	 * future does not linger in the map after the run has already failed or been cancelled.
	 */
	public void cancel(String toolCallId) {
		String runId = runIdByToolCallId.remove(toolCallId);
		CompletableFuture<ToolExecutionResult> future = pendingResults.remove(toolCallId);
		if (runId != null) {
			Set<String> ids = toolCallIdsByRunId.get(runId);
			if (ids != null) {
				ids.remove(toolCallId);
				if (ids.isEmpty()) {
					toolCallIdsByRunId.remove(runId);
				}
			}
		}
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

	public boolean isBoundToRun(String runId, String toolCallId) {
		return runId != null && runId.equals(runIdByToolCallId.get(toolCallId));
	}
}
