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
}
