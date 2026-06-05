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

/**
 * A group of HTTP tools generated from a single uploaded Swagger/OpenAPI spec.
 * Holds the resolved base URL and the keys of the generated tools so the whole
 * import can be listed and deleted as a unit. Auth is handled by the browser
 * (cookies), so no credentials are stored here.
 */
@Entity
@Table(name = "api_integrations",
	uniqueConstraints = @UniqueConstraint(name = "uk_api_integration_assistant_name",
		columnNames = {"assistant_id", "name"}))
public class ApiIntegrationEntity {

	@Id
	private String id;

	@Column(name = "assistant_id", nullable = false)
	private String assistantId;

	@Column(nullable = false)
	private String name;

	@Column(name = "base_url", length = 2_000)
	private String baseUrl;

	@Column(name = "allow_cross_origin", nullable = false)
	private boolean allowCrossOrigin;

	@Column(name = "tool_keys_json", columnDefinition = "TEXT")
	private String toolKeysJson;

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

	public String getBaseUrl() {
		return baseUrl;
	}

	public void setBaseUrl(String baseUrl) {
		this.baseUrl = baseUrl;
	}

	public boolean isAllowCrossOrigin() {
		return allowCrossOrigin;
	}

	public void setAllowCrossOrigin(boolean allowCrossOrigin) {
		this.allowCrossOrigin = allowCrossOrigin;
	}

	public String getToolKeysJson() {
		return toolKeysJson;
	}

	public void setToolKeysJson(String toolKeysJson) {
		this.toolKeysJson = toolKeysJson;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
