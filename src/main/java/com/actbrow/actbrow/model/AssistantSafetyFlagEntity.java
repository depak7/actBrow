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

/** A persisted per-assistant safety override (kill switch, shadow mode). */
@Entity
@Table(name = "assistant_safety_flags",
	uniqueConstraints = @UniqueConstraint(name = "uk_assistant_safety_flag",
		columnNames = {"assistant_id", "flag"}))
public class AssistantSafetyFlagEntity {

	@Id
	private String id;

	@Column(name = "assistant_id", nullable = false)
	private String assistantId;

	@Column(nullable = false, length = 64)
	private String flag;

	@Column(nullable = false)
	private boolean enabled;

	@Column(name = "updated_by")
	private String updatedBy;

	@Column(name = "updated_at", nullable = false)
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

	public String getAssistantId() {
		return assistantId;
	}

	public void setAssistantId(String assistantId) {
		this.assistantId = assistantId;
	}

	public String getFlag() {
		return flag;
	}

	public void setFlag(String flag) {
		this.flag = flag;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public String getUpdatedBy() {
		return updatedBy;
	}

	public void setUpdatedBy(String updatedBy) {
		this.updatedBy = updatedBy;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
