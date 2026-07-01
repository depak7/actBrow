package com.actbrow.actbrow.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.actbrow.actbrow.model.NavigationFlowEntity;

public interface NavigationFlowRepository extends JpaRepository<NavigationFlowEntity, String> {

	List<NavigationFlowEntity> findAllByAssistantIdOrderByCreatedAt(String assistantId);

	List<NavigationFlowEntity> findAllByAssistantIdAndEnabledTrueOrderByCreatedAt(String assistantId);

	Optional<NavigationFlowEntity> findByAssistantIdAndId(String assistantId, String id);

	Optional<NavigationFlowEntity> findByAssistant_IdAndName(String assistantId, String name);

	void deleteAllByAssistant_Id(String assistantId);
}
