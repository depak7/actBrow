package com.actbrow.actbrow.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.actbrow.actbrow.model.RunTraceEntity;

public interface RunTraceRepository extends JpaRepository<RunTraceEntity, String> {

	Optional<RunTraceEntity> findByRunId(String runId);
}
