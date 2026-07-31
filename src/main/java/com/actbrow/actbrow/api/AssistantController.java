package com.actbrow.actbrow.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.actbrow.actbrow.api.dto.AssistantResponse;
import com.actbrow.actbrow.api.dto.CreateAssistantRequest;
import com.actbrow.actbrow.service.AssistantService;
import com.actbrow.actbrow.service.ResourceAuthorizationService;

import jakarta.validation.Valid;

@RestController
@Validated
@RequestMapping("/v1/assistants")
public class AssistantController {

	private final AssistantService assistantService;
	private final ResourceAuthorizationService authorizationService;

	public AssistantController(AssistantService assistantService, ResourceAuthorizationService authorizationService) {
		this.assistantService = assistantService;
		this.authorizationService = authorizationService;
	}

	@PostMapping
	public AssistantResponse create(@Valid @RequestBody CreateAssistantRequest request,
		@RequestHeader(value = "X-Actbrow-Auth-Type", required = false) String authType,
		@RequestHeader(value = "X-User-Id", required = false) String userId) {
		authorizationService.requireAccount(authType, userId);
		CreateAssistantRequest owned = new CreateAssistantRequest(request.name(), request.systemPrompt(),
			request.model(), request.usePredefinedFlows(), userId);
		return assistantService.createOrUpdate(owned);
	}

	@PutMapping("/{id}")
	public AssistantResponse update(@PathVariable String id, @Valid @RequestBody CreateAssistantRequest request,
		@RequestHeader(value = "X-Actbrow-Auth-Type", required = false) String authType,
		@RequestHeader(value = "X-User-Id", required = false) String userId) {
		authorizationService.requireOwnedAssistant(id, authType, userId);
		CreateAssistantRequest owned = new CreateAssistantRequest(request.name(), request.systemPrompt(),
			request.model(), request.usePredefinedFlows(), userId);
		return assistantService.update(id, owned);
	}

	@GetMapping
	public List<AssistantResponse> list(
		@RequestHeader(value = "X-Actbrow-Auth-Type", required = false) String authType,
		@RequestHeader(value = "X-User-Id", required = false) String userId) {
		authorizationService.requireAccount(authType, userId);
		return assistantService.listByUser(userId);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable String id,
		@RequestHeader(value = "X-Actbrow-Auth-Type", required = false) String authType,
		@RequestHeader(value = "X-User-Id", required = false) String userId) {
		authorizationService.requireOwnedAssistant(id, authType, userId);
		assistantService.delete(id);
	}
}
