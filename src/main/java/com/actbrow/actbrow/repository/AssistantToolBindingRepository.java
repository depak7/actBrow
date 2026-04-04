package com.actbrow.actbrow.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.actbrow.actbrow.model.AssistantToolBindingEntity;

public interface AssistantToolBindingRepository extends JpaRepository<AssistantToolBindingEntity, String> {

	List<AssistantToolBindingEntity> findAllByAssistantId(String assistantId);

	Optional<AssistantToolBindingEntity> findByAssistantIdAndToolId(String assistantId, String toolId);

	List<AssistantToolBindingEntity> findAllByToolId(String toolId);
}
