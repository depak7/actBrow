package com.actbrow.actbrow.service;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.actbrow.actbrow.agent.ToolExecutionResult;

@Component
public class PendingClientToolStore {

	private final Map<String, CompletableFuture<ToolExecutionResult>> pendingResults = new ConcurrentHashMap<>();

	public CompletableFuture<ToolExecutionResult> register(String toolCallId) {
		CompletableFuture<ToolExecutionResult> future = new CompletableFuture<>();
		pendingResults.put(toolCallId, future);
		return future;
	}

	public void complete(String toolCallId, ToolExecutionResult result) {
		CompletableFuture<ToolExecutionResult> future = pendingResults.remove(toolCallId);
		if (future == null) {
			throw new IllegalArgumentException("Unknown toolCallId");
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
}
