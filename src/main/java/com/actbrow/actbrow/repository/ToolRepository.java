package com.actbrow.actbrow.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.actbrow.actbrow.model.ToolDefinitionEntity;

public interface ToolRepository extends JpaRepository<ToolDefinitionEntity, String> {

	Optional<ToolDefinitionEntity> findByKey(String key);
}
