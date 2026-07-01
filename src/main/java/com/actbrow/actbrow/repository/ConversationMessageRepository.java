package com.actbrow.actbrow.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.actbrow.actbrow.model.ConversationMessageEntity;

public interface ConversationMessageRepository extends JpaRepository<ConversationMessageEntity, String> {

	List<ConversationMessageEntity> findAllByConversationIdOrderByCreatedAtAscSeqAsc(String conversationId);

	Optional<ConversationMessageEntity> findTopByConversationIdOrderByCreatedAtDesc(String conversationId);

	long countByConversationId(String conversationId);

	void deleteByConversationId(String conversationId);
}
