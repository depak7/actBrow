package com.actbrow.actbrow.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.actbrow.actbrow.model.ConversationMessageEntity;

public interface ConversationMessageRepository extends JpaRepository<ConversationMessageEntity, String> {

	List<ConversationMessageEntity> findAllByConversationIdOrderByCreatedAtAsc(String conversationId);
}
