package com.actbrow.actbrow.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * Durable checkpoint of a run's progress: its phase, the step it reached, and the last planner
 * output / execution attempt / verifier result. One row per run (upserted on each transition).
 */
@Entity
@Table(name = "run_checkpoints")
public class RunCheckpointEntity {

	@Id
	private String id;

	@Column(nullable = false, unique = true)
	private String runId;

	@Column(nullable = false)
	private String conversationId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private RunPhase phase;

	@Column(nullable = false)
	private int stepIndex;

	@Column(columnDefinition = "TEXT")
	private String plannerOutcomeJson;

	@Column(columnDefinition = "TEXT")
	private String lastExecutionJson;

	@Column(columnDefinition = "TEXT")
	private String verifierResultJson;

	@Column(nullable = false)
	private Instant updatedAt;

	@PrePersist
	void prePersist() {
		if (id == null) {
			id = UUID.randomUUID().toString();
		}
		updatedAt = Instant.now();
	}

	@PreUpdate
	void preUpdate() {
		updatedAt = Instant.now();
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

	public RunPhase getPhase() {
		return phase;
	}

	public void setPhase(RunPhase phase) {
		this.phase = phase;
	}

	public int getStepIndex() {
		return stepIndex;
	}

	public void setStepIndex(int stepIndex) {
		this.stepIndex = stepIndex;
	}

	public String getPlannerOutcomeJson() {
		return plannerOutcomeJson;
	}

	public void setPlannerOutcomeJson(String plannerOutcomeJson) {
		this.plannerOutcomeJson = plannerOutcomeJson;
	}

	public String getLastExecutionJson() {
		return lastExecutionJson;
	}

	public void setLastExecutionJson(String lastExecutionJson) {
		this.lastExecutionJson = lastExecutionJson;
	}

	public String getVerifierResultJson() {
		return verifierResultJson;
	}

	public void setVerifierResultJson(String verifierResultJson) {
		this.verifierResultJson = verifierResultJson;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
