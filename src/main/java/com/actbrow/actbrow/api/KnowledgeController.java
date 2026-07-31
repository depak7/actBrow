package com.actbrow.actbrow.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.actbrow.actbrow.api.dto.KnowledgeDocumentRequest;
import com.actbrow.actbrow.api.dto.KnowledgeDocumentResponse;
import com.actbrow.actbrow.service.KnowledgeService;
import com.actbrow.actbrow.service.ResourceAuthorizationService;

import jakarta.validation.Valid;

@RestController
@Validated
@RequestMapping("/v1/assistants/{assistantId}/knowledge")
public class KnowledgeController {

	private final KnowledgeService knowledgeService;
	private final ResourceAuthorizationService authorizationService;

	public KnowledgeController(KnowledgeService knowledgeService, ResourceAuthorizationService authorizationService) {
		this.knowledgeService = knowledgeService;
		this.authorizationService = authorizationService;
	}

	@GetMapping
	public List<KnowledgeDocumentResponse> list(@PathVariable String assistantId,
		@RequestHeader(value = "X-Actbrow-Auth-Type", required = false) String authType,
		@RequestHeader(value = "X-User-Id", required = false) String userId) {
		authorizationService.requireOwnedAssistant(assistantId, authType, userId);
		return knowledgeService.listByAssistant(assistantId);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public KnowledgeDocumentResponse create(@PathVariable String assistantId,
		@Valid @RequestBody KnowledgeDocumentRequest request,
		@RequestHeader(value = "X-Actbrow-Auth-Type", required = false) String authType,
		@RequestHeader(value = "X-User-Id", required = false) String userId) {
		authorizationService.requireOwnedAssistant(assistantId, authType, userId);
		return knowledgeService.create(assistantId, request);
	}

	@DeleteMapping("/{knowledgeId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable String assistantId, @PathVariable String knowledgeId,
		@RequestHeader(value = "X-Actbrow-Auth-Type", required = false) String authType,
		@RequestHeader(value = "X-User-Id", required = false) String userId) {
		authorizationService.requireOwnedAssistant(assistantId, authType, userId);
		knowledgeService.delete(assistantId, knowledgeId);
	}
}
