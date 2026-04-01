package com.actbrow.actbrow.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "assistant_tool_bindings")
public class AssistantToolBindingEntity {

	@Id
	private String id;

	@Column(nullable = false)
	private String assistantId;

	@Column(nullable = false)
	private String toolId;

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

	public String getAssistantId() {
		return assistantId;
	}

	public void setAssistantId(String assistantId) {
		this.assistantId = assistantId;
	}

	public String getToolId() {
		return toolId;
	}

	public void setToolId(String toolId) {
		this.toolId = toolId;
	}
}
