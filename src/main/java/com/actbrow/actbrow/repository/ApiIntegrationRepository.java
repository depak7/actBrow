package com.actbrow.actbrow.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.actbrow.actbrow.model.ApiIntegrationEntity;

public interface ApiIntegrationRepository extends JpaRepository<ApiIntegrationEntity, String> {

	List<ApiIntegrationEntity> findAllByAssistantIdOrderByCreatedAtDesc(String assistantId);

	Optional<ApiIntegrationEntity> findByAssistantIdAndName(String assistantId, String name);
}
