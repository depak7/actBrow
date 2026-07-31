package com.actbrow.actbrow.service;

import org.springframework.stereotype.Service;

import com.actbrow.actbrow.api.NotFoundException;
import com.actbrow.actbrow.model.AssistantDefinitionEntity;
import com.actbrow.actbrow.model.ConversationEntity;
import com.actbrow.actbrow.model.RunEntity;
import com.actbrow.actbrow.repository.AssistantRepository;
import com.actbrow.actbrow.repository.ConversationRepository;
import com.actbrow.actbrow.repository.RunRepository;

/**
 * Central tenant/auth-type checks. Controllers must call these instead of trusting client headers alone.
 */
@Service
public class ResourceAuthorizationService {

	private final AssistantRepository assistantRepository;
	private final ConversationRepository conversationRepository;
	private final RunRepository runRepository;

	public ResourceAuthorizationService(AssistantRepository assistantRepository,
		ConversationRepository conversationRepository, RunRepository runRepository) {
		this.assistantRepository = assistantRepository;
		this.conversationRepository = conversationRepository;
		this.runRepository = runRepository;
	}

	public void requireAccount(String authType, String userId) {
		if (!"account".equals(authType) || userId == null || userId.isBlank()) {
			throw new IllegalArgumentException("Account API key is required");
		}
	}

	public AssistantDefinitionEntity requireOwnedAssistant(String assistantId, String authType, String userId) {
		requireAccount(authType, userId);
		AssistantDefinitionEntity assistant = assistantRepository.findById(assistantId)
			.orElseThrow(() -> new NotFoundException("Assistant not found"));
		if (!userId.equals(assistant.getUserId())) {
			throw new NotFoundException("Assistant not found");
		}
		return assistant;
	}

	public ConversationEntity requireAccessibleConversation(String conversationId, String authType, String userId,
		String authAssistantId) {
		ConversationEntity conversation = conversationRepository.findById(conversationId)
			.orElseThrow(() -> new NotFoundException("Conversation not found"));
		AssistantDefinitionEntity assistant = assistantRepository.findById(conversation.getAssistantId())
			.orElseThrow(() -> new NotFoundException("Conversation not found"));
		if ("widget".equals(authType)) {
			if (authAssistantId == null || !authAssistantId.equals(assistant.getId())) {
				throw new NotFoundException("Conversation not found");
			}
			return conversation;
		}
		if ("account".equals(authType)) {
			if (userId == null || !userId.equals(assistant.getUserId())) {
				throw new NotFoundException("Conversation not found");
			}
			return conversation;
		}
		throw new IllegalArgumentException("Unauthorized");
	}

	public RunEntity requireAccessibleRun(String runId, String authType, String userId, String authAssistantId) {
		RunEntity run = runRepository.findById(runId)
			.orElseThrow(() -> new NotFoundException("Run not found"));
		AssistantDefinitionEntity assistant = assistantRepository.findById(run.getAssistantId())
			.orElseThrow(() -> new NotFoundException("Run not found"));
		if ("widget".equals(authType)) {
			if (authAssistantId == null || !authAssistantId.equals(assistant.getId())) {
				throw new NotFoundException("Run not found");
			}
			return run;
		}
		if ("account".equals(authType)) {
			if (userId == null || !userId.equals(assistant.getUserId())) {
				throw new NotFoundException("Run not found");
			}
			return run;
		}
		throw new IllegalArgumentException("Unauthorized");
	}

	public void requireWidgetOrAccountForAssistant(String assistantId, String authType, String userId,
		String authAssistantId) {
		if ("widget".equals(authType)) {
			if (authAssistantId == null || !authAssistantId.equals(assistantId)) {
				throw new IllegalArgumentException("Widget key is not authorized for this assistant");
			}
			return;
		}
		requireOwnedAssistant(assistantId, authType, userId);
	}
}
