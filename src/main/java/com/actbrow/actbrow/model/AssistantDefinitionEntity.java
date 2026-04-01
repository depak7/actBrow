package com.actbrow.actbrow.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "assistants")
public class AssistantDefinitionEntity {

	@Id
	private String id;

	@Column(name = "assistant_key", nullable = false, unique = true)
	private String key;

	@Column(nullable = false)
	private String name;

	@Column(length = 4_000)
	private String systemPrompt;

	@Column(nullable = false)
	private String model;

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

	public String getKey() {
		return key;
	}

	public void setKey(String key) {
		this.key = key;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getSystemPrompt() {
		return systemPrompt;
	}

	public void setSystemPrompt(String systemPrompt) {
		this.systemPrompt = systemPrompt;
	}

	public String getModel() {
		return model;
	}

	public void setModel(String model) {
		this.model = model;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
