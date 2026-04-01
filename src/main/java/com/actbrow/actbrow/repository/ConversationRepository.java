package com.actbrow.actbrow.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.actbrow.actbrow.model.ConversationEntity;

public interface ConversationRepository extends JpaRepository<ConversationEntity, String> {
}
