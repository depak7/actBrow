package com.actbrow.actbrow.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "tools")
public class ToolDefinitionEntity {

	@Id
	private String id;

	@Column(name = "tool_key", nullable = false, unique = true)
	private String key;

	@Column(nullable = false)
	private String displayName;

	@Column(nullable = false, length = 2_000)
	private String description;

	@Column(nullable = false, columnDefinition = "TEXT")
	private String inputSchema;

	@Column(columnDefinition = "TEXT")
	private String outputSchema;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ToolType type;

	@Column(nullable = false)
	private String version;

	@Column(nullable = false)
	private boolean enabled;

	@Column(length = 2_000)
	private String executorRef;

	@Column(columnDefinition = "TEXT")
	private String defaultArguments;

	@Column(columnDefinition = "TEXT")
	private String metadata;

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

	public String getDisplayName() {
		return displayName;
	}

	public void setDisplayName(String displayName) {
		this.displayName = displayName;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public String getInputSchema() {
		return inputSchema;
	}

	public void setInputSchema(String inputSchema) {
		this.inputSchema = inputSchema;
	}

	public String getOutputSchema() {
		return outputSchema;
	}

	public void setOutputSchema(String outputSchema) {
		this.outputSchema = outputSchema;
	}

	public ToolType getType() {
		return type;
	}

	public void setType(ToolType type) {
		this.type = type;
	}

	public String getVersion() {
		return version;
	}

	public void setVersion(String version) {
		this.version = version;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public String getExecutorRef() {
		return executorRef;
	}

	public void setExecutorRef(String executorRef) {
		this.executorRef = executorRef;
	}

	public String getDefaultArguments() {
		return defaultArguments;
	}

	public void setDefaultArguments(String defaultArguments) {
		this.defaultArguments = defaultArguments;
	}

	public String getMetadata() {
		return metadata;
	}

	public void setMetadata(String metadata) {
		this.metadata = metadata;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
