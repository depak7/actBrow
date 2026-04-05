package com.actbrow.actbrow.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.actbrow.actbrow.model.RunEntity;

public interface RunRepository extends JpaRepository<RunEntity, String> {

	List<RunEntity> findAllByConversationId(String conversationId);
}
