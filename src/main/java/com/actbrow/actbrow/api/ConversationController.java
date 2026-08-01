package com.actbrow.actbrow.api;

import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.actbrow.actbrow.api.dto.ConversationMessageResponse;
import com.actbrow.actbrow.api.dto.ConversationRequest;
import com.actbrow.actbrow.api.dto.ConversationResponse;
import com.actbrow.actbrow.api.dto.ConversationSummaryResponse;
import com.actbrow.actbrow.api.dto.RunResponse;
import com.actbrow.actbrow.api.dto.TurnRequest;
import com.actbrow.actbrow.conversation.UserMessageDisplay;
import com.actbrow.actbrow.model.ConversationMessageRole;
import com.actbrow.actbrow.service.ConversationService;
import com.actbrow.actbrow.service.ResourceAuthorizationService;
import com.actbrow.actbrow.service.RunService;

import jakarta.validation.Valid;

@RestController
@Validated
@RequestMapping("/v1/conversations")
public class ConversationController {

	private final ConversationService conversationService;
	private final RunService runService;
	private final ResourceAuthorizationService authorizationService;

	public ConversationController(ConversationService conversationService, RunService runService,
		ResourceAuthorizationService authorizationService) {
		this.conversationService = conversationService;
		this.runService = runService;
		this.authorizationService = authorizationService;
	}

	@PostMapping
	public ConversationResponse create(@Valid @RequestBody ConversationRequest request,
		@RequestHeader(value = "X-Actbrow-Auth-Type", required = false) String authType,
		@RequestHeader(value = "X-Actbrow-Assistant-Id", required = false) String authAssistantId,
		@RequestHeader(value = "X-User-Id", required = false) String userId) {
		authorizationService.requireWidgetOrAccountForAssistant(request.assistantId(), authType, userId,
			authAssistantId);
		return conversationService.create(request);
	}

	@GetMapping
	public List<ConversationSummaryResponse> list(
		@RequestHeader(value = "X-Actbrow-Auth-Type", required = false) String authType,
		@RequestHeader(value = "X-User-Id", required = false) String userId) {
		authorizationService.requireAccount(authType, userId);
		return conversationService.listForUser(userId);
	}

	@PostMapping("/{conversationId}/turns")
	public RunResponse createTurn(@PathVariable String conversationId, @Valid @RequestBody TurnRequest request,
		@RequestHeader(value = "X-Actbrow-Auth-Type", required = false) String authType,
		@RequestHeader(value = "X-Actbrow-Assistant-Id", required = false) String authAssistantId,
		@RequestHeader(value = "X-User-Id", required = false) String userId) {
		authorizationService.requireAccessibleConversation(conversationId, authType, userId, authAssistantId);
		return runService.startRun(conversationId, request);
	}

	@GetMapping("/{conversationId}/messages")
	public List<ConversationMessageResponse> listMessages(@PathVariable String conversationId,
		@RequestHeader(value = "X-Actbrow-Auth-Type", required = false) String authType,
		@RequestHeader(value = "X-Actbrow-Assistant-Id", required = false) String authAssistantId,
		@RequestHeader(value = "X-User-Id", required = false) String userId) {
		authorizationService.requireAccessibleConversation(conversationId, authType, userId, authAssistantId);
		return conversationService.listMessages(conversationId).stream()
			// The conversation table doubles as the model's working transcript, so it also holds rows
			// that are machine plumbing rather than anything a person said: TOOL results and the
			// ASSISTANT tool-call envelope. Rendering those verbatim shows raw JSON as chat bubbles.
			// Run inspection is the right surface for them, not the message list.
			.filter(m -> m.getRole() != ConversationMessageRole.TOOL)
			.filter(m -> !isToolCallEnvelope(m.getRole(), m.getContent()))
			.map(m -> {
				String content = m.getContent();
				if (m.getRole() == ConversationMessageRole.USER) {
					content = UserMessageDisplay.stripStoredAppendix(content);
				}
				return new ConversationMessageResponse(m.getId(), m.getRole().name(), content, m.getToolCallId(),
					m.getCreatedAt());
			})
			.toList();
	}

	/** Matches the {@code [tool_calls][...]} envelope RunService stores for provider replay. */
	private static boolean isToolCallEnvelope(ConversationMessageRole role, String content) {
		return role == ConversationMessageRole.ASSISTANT && content != null && content.startsWith("[tool_calls]");
	}

	@DeleteMapping("/{conversationId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteConversation(@PathVariable String conversationId,
		@RequestHeader(value = "X-Actbrow-Auth-Type", required = false) String authType,
		@RequestHeader(value = "X-Actbrow-Assistant-Id", required = false) String authAssistantId,
		@RequestHeader(value = "X-User-Id", required = false) String userId) {
		authorizationService.requireAccessibleConversation(conversationId, authType, userId, authAssistantId);
		runService.deleteConversationCascade(conversationId);
	}
}
