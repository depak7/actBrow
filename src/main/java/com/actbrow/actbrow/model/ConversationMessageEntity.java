package com.actbrow.actbrow.model;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "conversation_messages")
public class ConversationMessageEntity {

	@Id
	private String id;

	@Column(nullable = false)
	private String conversationId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ConversationMessageRole role;

	@Column(nullable = false, columnDefinition = "TEXT")
	private String content;

	@Column
	private String toolCallId;

	@Column(nullable = false)
	private Instant createdAt;

	/**
	 * Monotonic tiebreaker for {@link #createdAt}. Two messages appended in the same millisecond
	 * (e.g. an ASSISTANT tool_calls envelope and its TOOL result) would otherwise sort
	 * non-deterministically and break tool_calls/tool pairing for the model. Ordered by
	 * (createdAt, seq). Process-local counter — good enough as an intra-instant tiebreaker.
	 */
	@Column
	private Long seq;

	private static final AtomicLong SEQ = new AtomicLong();

	@PrePersist
	void prePersist() {
		if (id == null) {
			id = UUID.randomUUID().toString();
		}
		if (createdAt == null) {
			createdAt = Instant.now();
		}
		if (seq == null) {
			seq = SEQ.incrementAndGet();
		}
	}

	public Long getSeq() {
		return seq;
	}

	public void setSeq(Long seq) {
		this.seq = seq;
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getConversationId() {
		return conversationId;
	}

	public void setConversationId(String conversationId) {
		this.conversationId = conversationId;
	}

	public ConversationMessageRole getRole() {
		return role;
	}

	public void setRole(ConversationMessageRole role) {
		this.role = role;
	}

	public String getContent() {
		return content;
	}

	public void setContent(String content) {
		this.content = content;
	}

	public String getToolCallId() {
		return toolCallId;
	}

	public void setToolCallId(String toolCallId) {
		this.toolCallId = toolCallId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
