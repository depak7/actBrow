package com.actbrow.actbrow.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "navigation_flows")
public class NavigationFlowEntity {

	@Id
	private String id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "assistant_id", nullable = false)
	private AssistantDefinitionEntity assistant;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false)
	private String triggerPhrase;

	@Column(columnDefinition = "TEXT", nullable = false)
	private String stepsJson;

	@Column(nullable = false)
	private boolean enabled;

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

	public void setId(String id) {
		this.id = id;
	}

	public AssistantDefinitionEntity getAssistant() {
		return assistant;
	}

	public void setAssistant(AssistantDefinitionEntity assistant) {
		this.assistant = assistant;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getTriggerPhrase() {
		return triggerPhrase;
	}

	public void setTriggerPhrase(String triggerPhrase) {
		this.triggerPhrase = triggerPhrase;
	}

	public String getStepsJson() {
		return stepsJson;
	}

	public void setStepsJson(String stepsJson) {
		this.stepsJson = stepsJson;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
