package com.actbrow.actbrow.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.actbrow.actbrow.model.AssistantDefinitionEntity;

public interface AssistantRepository extends JpaRepository<AssistantDefinitionEntity, String> {

	Optional<AssistantDefinitionEntity> findByKey(String key);

	Optional<AssistantDefinitionEntity> findBySetupKey(String setupKey);

	Optional<AssistantDefinitionEntity> findByWidgetKey(String widgetKey);

	List<AssistantDefinitionEntity> findAllByUserId(String userId);
}
