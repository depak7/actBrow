package com.actbrow.actbrow.api;

import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.actbrow.actbrow.api.dto.ConversationMessageResponse;
import com.actbrow.actbrow.api.dto.ConversationRequest;
import com.actbrow.actbrow.api.dto.ConversationResponse;
import com.actbrow.actbrow.api.dto.RunResponse;
import com.actbrow.actbrow.api.dto.TurnRequest;
import com.actbrow.actbrow.service.ConversationService;
import com.actbrow.actbrow.service.RunService;

import jakarta.validation.Valid;

@RestController
@Validated
@RequestMapping("/v1/conversations")
public class ConversationController {

	private final ConversationService conversationService;
	private final RunService runService;

	public ConversationController(ConversationService conversationService, RunService runService) {
		this.conversationService = conversationService;
		this.runService = runService;
	}

	@PostMapping
	public ConversationResponse create(@Valid @RequestBody ConversationRequest request) {
		return conversationService.create(request);
	}

	@PostMapping("/{conversationId}/turns")
	public RunResponse createTurn(@PathVariable String conversationId, @Valid @RequestBody TurnRequest request) {
		return runService.startRun(conversationId, request);
	}

	@GetMapping("/{conversationId}/messages")
	public List<ConversationMessageResponse> listMessages(@PathVariable String conversationId) {
		conversationService.requireConversation(conversationId);
		return conversationService.listMessages(conversationId).stream()
			.map(m -> new ConversationMessageResponse(
				m.getId(),
				m.getRole().name(),
				m.getContent(),
				m.getCreatedAt()))
			.toList();
	}
}
