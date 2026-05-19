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
	private boolean usePredefinedFlows;

	@Column(name = "api_key", nullable = false, unique = true)
	private String apiKey;

	@Column(name = "setup_key", unique = true)
	private String setupKey;

	@Column(name = "widget_key", unique = true)
	private String widgetKey;

	@Column(name = "allowed_origins_json", columnDefinition = "TEXT")
	private String allowedOriginsJson;

	@Column(name = "last_synced_at")
	private Instant lastSyncedAt;

	@Column(name = "last_sync_summary_json", columnDefinition = "TEXT")
	private String lastSyncSummaryJson;

	@Column(name = "user_id", nullable = false)
	private String userId;

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

	public boolean isUsePredefinedFlows() {
		return usePredefinedFlows;
	}

	public void setUsePredefinedFlows(boolean usePredefinedFlows) {
		this.usePredefinedFlows = usePredefinedFlows;
	}

	public String getApiKey() {
		return apiKey;
	}

	public void setApiKey(String apiKey) {
		this.apiKey = apiKey;
	}

	public String getSetupKey() {
		return setupKey;
	}

	public void setSetupKey(String setupKey) {
		this.setupKey = setupKey;
	}

	public String getWidgetKey() {
		return widgetKey;
	}

	public void setWidgetKey(String widgetKey) {
		this.widgetKey = widgetKey;
	}

	public String getAllowedOriginsJson() {
		return allowedOriginsJson;
	}

	public void setAllowedOriginsJson(String allowedOriginsJson) {
		this.allowedOriginsJson = allowedOriginsJson;
	}

	public Instant getLastSyncedAt() {
		return lastSyncedAt;
	}

	public void setLastSyncedAt(Instant lastSyncedAt) {
		this.lastSyncedAt = lastSyncedAt;
	}

	public String getLastSyncSummaryJson() {
		return lastSyncSummaryJson;
	}

	public void setLastSyncSummaryJson(String lastSyncSummaryJson) {
		this.lastSyncSummaryJson = lastSyncSummaryJson;
	}

	public String getUserId() {
		return userId;
	}

	public void setUserId(String userId) {
		this.userId = userId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
