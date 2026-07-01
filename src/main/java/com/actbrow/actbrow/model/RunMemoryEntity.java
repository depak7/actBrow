package com.actbrow.actbrow.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "run_memories")
public class RunMemoryEntity {

	@Id
	private String id;

	@Column(nullable = false, unique = true)
	private String runId;

	@Column(nullable = false)
	private String conversationId;

	@Column(columnDefinition = "TEXT")
	private String objective;

	@Column(columnDefinition = "TEXT")
	private String currentStepGoal;

	@Column(columnDefinition = "TEXT")
	private String successCriteria;

	@Column(columnDefinition = "TEXT")
	private String knownEntitiesJson;

	@Column(columnDefinition = "TEXT")
	private String lastActionJson;

	@Column(columnDefinition = "TEXT")
	private String lastFailuresJson;

	@Column(length = 2_000)
	private String blockedReason;

	@Column(columnDefinition = "TEXT")
	private String summaryJson;

	@Column(nullable = false)
	private Instant createdAt;

	@Column(nullable = false)
	private Instant updatedAt;

	@PrePersist
	void prePersist() {
		if (id == null) {
			id = UUID.randomUUID().toString();
		}
		Instant now = Instant.now();
		if (createdAt == null) {
			createdAt = now;
		}
		if (updatedAt == null) {
			updatedAt = now;
		}
	}

	@PreUpdate
	void preUpdate() {
		updatedAt = Instant.now();
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
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

	public String getObjective() {
		return objective;
	}

	public void setObjective(String objective) {
		this.objective = objective;
	}

	public String getCurrentStepGoal() {
		return currentStepGoal;
	}

	public void setCurrentStepGoal(String currentStepGoal) {
		this.currentStepGoal = currentStepGoal;
	}

	public String getSuccessCriteria() {
		return successCriteria;
	}

	public void setSuccessCriteria(String successCriteria) {
		this.successCriteria = successCriteria;
	}

	public String getKnownEntitiesJson() {
		return knownEntitiesJson;
	}

	public void setKnownEntitiesJson(String knownEntitiesJson) {
		this.knownEntitiesJson = knownEntitiesJson;
	}

	public String getLastActionJson() {
		return lastActionJson;
	}

	public void setLastActionJson(String lastActionJson) {
		this.lastActionJson = lastActionJson;
	}

	public String getLastFailuresJson() {
		return lastFailuresJson;
	}

	public void setLastFailuresJson(String lastFailuresJson) {
		this.lastFailuresJson = lastFailuresJson;
	}

	public String getBlockedReason() {
		return blockedReason;
	}

	public void setBlockedReason(String blockedReason) {
		this.blockedReason = blockedReason;
	}

	public String getSummaryJson() {
		return summaryJson;
	}

	public void setSummaryJson(String summaryJson) {
		this.summaryJson = summaryJson;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
