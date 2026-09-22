package com.actbrow.actbrow.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.actbrow.actbrow.model.AssistantSafetyFlagEntity;

public interface AssistantSafetyFlagRepository extends JpaRepository<AssistantSafetyFlagEntity, String> {

	List<AssistantSafetyFlagEntity> findAllByAssistantId(String assistantId);

	Optional<AssistantSafetyFlagEntity> findByAssistantIdAndFlag(String assistantId, String flag);
}
