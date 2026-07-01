package com.actbrow.actbrow.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * A structured, queryable record of a completed run for evaluation (Phase 5): prompt/toolset
 * versions, planning outcomes, execution attempts, verifier decisions, final outcome, latency and
 * tool counts. One row per run, written when the run reaches a terminal state.
 */
@Entity
@Table(name = "run_traces")
public class RunTraceEntity {

	@Id
	private String id;

	@Column(nullable = false, unique = true)
	private String runId;

	@Column(nullable = false)
	private String conversationId;

	@Column(nullable = false)
	private String assistantId;

	@Column
	private String promptVersion;

	@Column
	private String toolsetVersion;

	@Column(columnDefinition = "TEXT")
	private String planningOutcomes;

	@Column(columnDefinition = "TEXT")
	private String verifierDecisions;

	@Column
	private int executionAttempts;

	@Column
	private int toolCallCount;

	@Column
	private String finalOutcome;

	@Column
	private long latencyMs;

	@Column(nullable = false)
	private Instant createdAt;

	@PrePersist
	void prePersist() {
		if (id == null) {
			id = UUID.randomUUID().toString();
		}
		if (createdAt == null) {
			createdAt = Instant.now();
		}
	}

	public String getId() {
		return id;
	}

	public String getRunId() {
		return runId;
	}

	public void setRunId(String runId) {
		this.runId = runId;
	}

	public String getConversationId() {
		return conversationId;
	}

	public void setConversationId(String conversationId) {
		this.conversationId = conversationId;
	}

	public String getAssistantId() {
		return assistantId;
	}

	public void setAssistantId(String assistantId) {
		this.assistantId = assistantId;
	}

	public String getPromptVersion() {
		return promptVersion;
	}

	public void setPromptVersion(String promptVersion) {
		this.promptVersion = promptVersion;
	}

	public String getToolsetVersion() {
		return toolsetVersion;
	}

	public void setToolsetVersion(String toolsetVersion) {
		this.toolsetVersion = toolsetVersion;
	}

	public String getPlanningOutcomes() {
		return planningOutcomes;
	}

	public void setPlanningOutcomes(String planningOutcomes) {
		this.planningOutcomes = planningOutcomes;
	}

	public String getVerifierDecisions() {
		return verifierDecisions;
	}

	public void setVerifierDecisions(String verifierDecisions) {
		this.verifierDecisions = verifierDecisions;
	}

	public int getExecutionAttempts() {
		return executionAttempts;
	}

	public void setExecutionAttempts(int executionAttempts) {
		this.executionAttempts = executionAttempts;
	}

	public int getToolCallCount() {
		return toolCallCount;
	}

	public void setToolCallCount(int toolCallCount) {
		this.toolCallCount = toolCallCount;
	}

	public String getFinalOutcome() {
		return finalOutcome;
	}

	public void setFinalOutcome(String finalOutcome) {
		this.finalOutcome = finalOutcome;
	}

	public long getLatencyMs() {
		return latencyMs;
	}

	public void setLatencyMs(long latencyMs) {
		this.latencyMs = latencyMs;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
