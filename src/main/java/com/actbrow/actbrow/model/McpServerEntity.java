package com.actbrow.actbrow.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "mcp_servers",
	uniqueConstraints = @UniqueConstraint(name = "uk_mcp_server_assistant_name",
		columnNames = {"assistant_id", "name"}))
public class McpServerEntity {

	@Id
	private String id;

	@Column(name = "assistant_id", nullable = false)
	private String assistantId;

	@Column(nullable = false)
	private String name;

	@Column(name = "server_url", nullable = false, length = 2_000)
	private String serverUrl;

	@Column(name = "auth_headers_json", columnDefinition = "TEXT")
	private String authHeadersJson;

	@Column(nullable = false)
	private boolean enabled;

	@Column(name = "tool_keys_json", columnDefinition = "TEXT")
	private String toolKeysJson;

	@Column(name = "last_synced_at")
	private Instant lastSyncedAt;

	@Column(nullable = false)
	private Instant createdAt;

	@Column
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

	public String getAssistantId() {
		return assistantId;
	}

	public void setAssistantId(String assistantId) {
		this.assistantId = assistantId;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getServerUrl() {
		return serverUrl;
	}

	public void setServerUrl(String serverUrl) {
		this.serverUrl = serverUrl;
	}

	public String getAuthHeadersJson() {
		return authHeadersJson;
	}

	public void setAuthHeadersJson(String authHeadersJson) {
		this.authHeadersJson = authHeadersJson;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public String getToolKeysJson() {
		return toolKeysJson;
	}

	public void setToolKeysJson(String toolKeysJson) {
		this.toolKeysJson = toolKeysJson;
	}

	public Instant getLastSyncedAt() {
		return lastSyncedAt;
	}

	public void setLastSyncedAt(Instant lastSyncedAt) {
		this.lastSyncedAt = lastSyncedAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
