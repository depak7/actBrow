package com.actbrow.actbrow.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.actbrow.actbrow.model.McpServerEntity;

public interface McpServerRepository extends JpaRepository<McpServerEntity, String> {

	List<McpServerEntity> findAllByAssistantIdOrderByCreatedAtDesc(String assistantId);

	Optional<McpServerEntity> findByAssistantIdAndName(String assistantId, String name);

	Optional<McpServerEntity> findByIdAndAssistantId(String id, String assistantId);

	void deleteByAssistantId(String assistantId);
}
