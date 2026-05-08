package com.actbrow.actbrow.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.actbrow.actbrow.api.dto.KnowledgeDocumentRequest;
import com.actbrow.actbrow.api.dto.KnowledgeDocumentResponse;
import com.actbrow.actbrow.service.AssistantService;
import com.actbrow.actbrow.service.KnowledgeService;

import jakarta.validation.Valid;

@RestController
@Validated
@RequestMapping("/v1/assistants/{assistantId}/knowledge")
public class KnowledgeController {

	private final AssistantService assistantService;
	private final KnowledgeService knowledgeService;

	public KnowledgeController(AssistantService assistantService, KnowledgeService knowledgeService) {
		this.assistantService = assistantService;
		this.knowledgeService = knowledgeService;
	}

	@GetMapping
	public List<KnowledgeDocumentResponse> list(@PathVariable String assistantId) {
		assistantService.requireEntity(assistantId);
		return knowledgeService.listByAssistant(assistantId);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public KnowledgeDocumentResponse create(@PathVariable String assistantId,
		@Valid @RequestBody KnowledgeDocumentRequest request) {
		assistantService.requireEntity(assistantId);
		return knowledgeService.create(assistantId, request);
	}

	@DeleteMapping("/{knowledgeId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable String assistantId, @PathVariable String knowledgeId) {
		assistantService.requireEntity(assistantId);
		knowledgeService.delete(assistantId, knowledgeId);
	}
}
