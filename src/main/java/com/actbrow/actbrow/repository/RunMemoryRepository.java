package com.actbrow.actbrow.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.actbrow.actbrow.model.RunMemoryEntity;

public interface RunMemoryRepository extends JpaRepository<RunMemoryEntity, String> {

	Optional<RunMemoryEntity> findByRunId(String runId);

	void deleteByRunId(String runId);

	void deleteByConversationId(String conversationId);
}
