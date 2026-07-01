package com.actbrow.actbrow.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.actbrow.actbrow.model.RunCheckpointEntity;

public interface RunCheckpointRepository extends JpaRepository<RunCheckpointEntity, String> {

	Optional<RunCheckpointEntity> findByRunId(String runId);

	void deleteByRunId(String runId);
}
