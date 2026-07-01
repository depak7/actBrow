package com.actbrow.actbrow.service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.actbrow.actbrow.api.NotFoundException;
import com.actbrow.actbrow.api.dto.ConversationRequest;
import com.actbrow.actbrow.api.dto.ConversationResponse;
import com.actbrow.actbrow.api.dto.ConversationSummaryResponse;
import com.actbrow.actbrow.conversation.UserMessageDisplay;
import com.actbrow.actbrow.model.AssistantDefinitionEntity;
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
			.orElseThrow(() -> new NotFoundException("Conversation not found"));
	}

	public boolean exists(String conversationId) {
		return conversationRepository.existsById(conversationId);
	}

	public List<ConversationSummaryResponse> listForUser(String userId) {
		List<AssistantDefinitionEntity> assistants = assistantService.listEntitiesByUser(userId);
		if (assistants.isEmpty()) {
			return List.of();
		}
		Map<String, AssistantDefinitionEntity> assistantsById = assistants.stream()
			.collect(Collectors.toMap(AssistantDefinitionEntity::getId, Function.identity()));
		List<String> assistantIds = assistants.stream()
			.map(AssistantDefinitionEntity::getId)
			.toList();

		return conversationRepository.findAllByAssistantIdInOrderByCreatedAtDesc(assistantIds).stream()
			.map(conversation -> toSummary(conversation, assistantsById.get(conversation.getAssistantId())))
			.toList();
	}

	@Transactional
	public void deleteMessagesAndConversation(String conversationId) {
		messageRepository.deleteByConversationId(conversationId);
		conversationRepository.deleteById(conversationId);
	}

	public ConversationMessageEntity appendMessage(String conversationId, ConversationMessageRole role, String content) {
		return appendMessage(conversationId, role, content, null);
	}

	public ConversationMessageEntity appendMessage(String conversationId, ConversationMessageRole role, String content, String toolCallId) {
		ConversationMessageEntity entity = new ConversationMessageEntity();
		entity.setConversationId(conversationId);
		entity.setRole(role);
		entity.setContent(content);
		entity.setToolCallId(toolCallId);
		return messageRepository.save(entity);
	}

	public List<ConversationMessageEntity> listMessages(String conversationId) {
		return messageRepository.findAllByConversationIdOrderByCreatedAtAscSeqAsc(conversationId);
	}

	private ConversationSummaryResponse toSummary(ConversationEntity conversation, AssistantDefinitionEntity assistant) {
		var lastMessage = messageRepository.findTopByConversationIdOrderByCreatedAtDesc(conversation.getId());
		long messageCount = messageRepository.countByConversationId(conversation.getId());
		String preview = lastMessage.map(ConversationMessageEntity::getContent).map(this::preview).orElse("");
		String role = lastMessage.map(m -> m.getRole().name()).orElse(null);

		return new ConversationSummaryResponse(
			conversation.getId(),
			conversation.getAssistantId(),
			assistant == null ? "Unknown assistant" : assistant.getName(),
			conversation.getCreatedAt(),
			lastMessage.map(ConversationMessageEntity::getCreatedAt).orElse(null),
			messageCount,
			role,
			preview
		);
	}

	private String preview(String content) {
		String displayContent = UserMessageDisplay.stripStoredAppendix(content)
			.replaceAll("\\s+", " ")
			.trim();
		if (displayContent.length() <= 140) {
			return displayContent;
		}
		return displayContent.substring(0, 137) + "...";
	}
}
