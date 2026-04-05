package com.actbrow.actbrow.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.actbrow.actbrow.api.dto.ConversationRequest;
import com.actbrow.actbrow.api.dto.ConversationResponse;
import com.actbrow.actbrow.model.ConversationEntity;
import com.actbrow.actbrow.model.ConversationMessageEntity;
import com.actbrow.actbrow.model.ConversationMessageRole;
import com.actbrow.actbrow.repository.ConversationMessageRepository;
import com.actbrow.actbrow.repository.ConversationRepository;

@Service
public class ConversationService {

	private final ConversationRepository conversationRepository;
	private final ConversationMessageRepository messageRepository;
	private final AssistantService assistantService;

	public ConversationService(ConversationRepository conversationRepository,
		ConversationMessageRepository messageRepository, AssistantService assistantService) {
		this.conversationRepository = conversationRepository;
		this.messageRepository = messageRepository;
		this.assistantService = assistantService;
	}

	public ConversationResponse create(ConversationRequest request) {
		assistantService.requireEntity(request.assistantId());
		ConversationEntity entity = new ConversationEntity();
		entity.setAssistantId(request.assistantId());
		ConversationEntity saved = conversationRepository.save(entity);
		return new ConversationResponse(saved.getId(), saved.getAssistantId(), saved.getCreatedAt());
	}

	public ConversationEntity requireConversation(String conversationId) {
		return conversationRepository.findById(conversationId)
			.orElseThrow(() -> new IllegalArgumentException("Conversation not found"));
	}

	public boolean exists(String conversationId) {
		return conversationRepository.existsById(conversationId);
	}

	@Transactional
	public void deleteMessagesAndConversation(String conversationId) {
		messageRepository.deleteByConversationId(conversationId);
		conversationRepository.deleteById(conversationId);
	}

	public ConversationMessageEntity appendMessage(String conversationId, ConversationMessageRole role, String content) {
		ConversationMessageEntity entity = new ConversationMessageEntity();
		entity.setConversationId(conversationId);
		entity.setRole(role);
		entity.setContent(content);
		return messageRepository.save(entity);
	}

	public List<ConversationMessageEntity> listMessages(String conversationId) {
		return messageRepository.findAllByConversationIdOrderByCreatedAtAsc(conversationId);
	}
}
