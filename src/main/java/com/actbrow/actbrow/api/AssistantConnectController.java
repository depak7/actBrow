package com.actbrow.actbrow.api;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.actbrow.actbrow.api.dto.AssistantConnectResponse;
import com.actbrow.actbrow.api.dto.AssistantSyncRequest;
import com.actbrow.actbrow.api.dto.AssistantSyncResponse;
import com.actbrow.actbrow.service.AssistantConnectService;

import jakarta.validation.Valid;

@RestController
@Validated
@RequestMapping("/v1/assistants/{assistantId}")
public class AssistantConnectController {

	private final AssistantConnectService assistantConnectService;

	public AssistantConnectController(AssistantConnectService assistantConnectService) {
		this.assistantConnectService = assistantConnectService;
	}

	@GetMapping("/connect")
	public AssistantConnectResponse connect(@PathVariable String assistantId,
		@RequestHeader(value = "X-User-Id", required = false) String userId) {
		return assistantConnectService.getConnect(assistantId, userId);
	}

	@PutMapping("/sync")
	public AssistantSyncResponse sync(@PathVariable String assistantId,
		@RequestHeader(value = "X-User-Id", required = false) String userId,
		@RequestHeader(value = "X-Actbrow-Auth-Type", required = false) String authType,
		@RequestHeader(value = "X-Actbrow-Assistant-Id", required = false) String authAssistantId,
		@Valid @RequestBody AssistantSyncRequest request) {
		return assistantConnectService.sync(assistantId, userId, authType, authAssistantId, request);
	}
}
