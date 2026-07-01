package com.actbrow.actbrow.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.actbrow.actbrow.model.KnowledgeDocumentEntity;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocumentEntity, String> {

	List<KnowledgeDocumentEntity> findAllByAssistantIdOrderByUpdatedAtDesc(String assistantId);

	List<KnowledgeDocumentEntity> findAllByAssistantIdAndEnabledTrueOrderByUpdatedAtDesc(String assistantId);

	Optional<KnowledgeDocumentEntity> findByAssistantIdAndId(String assistantId, String id);

	Optional<KnowledgeDocumentEntity> findByAssistantIdAndTitle(String assistantId, String title);

	void deleteAllByAssistantId(String assistantId);
}
