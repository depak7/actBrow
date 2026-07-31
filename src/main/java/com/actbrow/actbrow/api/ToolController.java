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

import com.actbrow.actbrow.api.dto.AttachToolRequest;
import com.actbrow.actbrow.api.dto.CreateAssistantToolRequest;
import com.actbrow.actbrow.api.dto.ToolRequest;
import com.actbrow.actbrow.api.dto.ToolResponse;
import com.actbrow.actbrow.service.ResourceAuthorizationService;
import com.actbrow.actbrow.service.ToolService;

import jakarta.validation.Valid;

@RestController
@Validated
@RequestMapping("/v1")
public class ToolController {

	private final ToolService toolService;
	private final ResourceAuthorizationService authorizationService;

	public ToolController(ToolService toolService, ResourceAuthorizationService authorizationService) {
		this.toolService = toolService;
		this.authorizationService = authorizationService;
	}

	@PostMapping("/tools")
	@ResponseStatus(HttpStatus.CREATED)
	public ToolResponse create(@Valid @RequestBody ToolRequest request,
		@RequestHeader(value = "X-Actbrow-Auth-Type", required = false) String authType,
		@RequestHeader(value = "X-User-Id", required = false) String userId) {
		authorizationService.requireAccount(authType, userId);
		return toolService.create(request);
	}

	@PostMapping("/tools/attach")
	@ResponseStatus(HttpStatus.CREATED)
	public ToolResponse createAndAttach(@Valid @RequestBody CreateAssistantToolRequest request,
		@RequestHeader(value = "X-Actbrow-Auth-Type", required = false) String authType,
		@RequestHeader(value = "X-User-Id", required = false) String userId) {
		authorizationService.requireOwnedAssistant(request.assistantId(), authType, userId);
		return toolService.createAndAttach(request.assistantId(), request.toToolRequest());
	}

	@PutMapping("/tools/{toolId}")
	public ToolResponse update(@PathVariable String toolId, @Valid @RequestBody ToolRequest request,
		@RequestHeader(value = "X-Actbrow-Auth-Type", required = false) String authType,
		@RequestHeader(value = "X-User-Id", required = false) String userId) {
		authorizationService.requireAccount(authType, userId);
		toolService.requireOwnedOrUnboundTool(toolId, userId);
		return toolService.update(toolId, request);
	}

	@DeleteMapping("/tools/{toolId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable String toolId,
		@RequestHeader(value = "X-Actbrow-Auth-Type", required = false) String authType,
		@RequestHeader(value = "X-User-Id", required = false) String userId) {
		authorizationService.requireAccount(authType, userId);
		toolService.requireOwnedOrUnboundTool(toolId, userId);
		toolService.delete(toolId);
	}

	@GetMapping("/tools")
	public List<ToolResponse> list(
		@RequestHeader(value = "X-Actbrow-Auth-Type", required = false) String authType,
		@RequestHeader(value = "X-User-Id", required = false) String userId) {
		authorizationService.requireAccount(authType, userId);
		return toolService.listForUser(userId);
	}

	@GetMapping("/assistants/{assistantId}/tools")
	public List<ToolResponse> listAssistantTools(@PathVariable String assistantId,
		@RequestHeader(value = "X-Actbrow-Auth-Type", required = false) String authType,
		@RequestHeader(value = "X-User-Id", required = false) String userId) {
		authorizationService.requireOwnedAssistant(assistantId, authType, userId);
		return toolService.listAssistantTools(assistantId);
	}

	@PostMapping("/assistants/{assistantId}/tools")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void attachTool(@PathVariable String assistantId, @Valid @RequestBody AttachToolRequest request,
		@RequestHeader(value = "X-Actbrow-Auth-Type", required = false) String authType,
		@RequestHeader(value = "X-User-Id", required = false) String userId) {
		authorizationService.requireOwnedAssistant(assistantId, authType, userId);
		toolService.attachTool(assistantId, request.toolId());
	}

	@DeleteMapping("/assistants/{assistantId}/tools/{toolId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void detachTool(@PathVariable String assistantId, @PathVariable String toolId,
		@RequestHeader(value = "X-Actbrow-Auth-Type", required = false) String authType,
		@RequestHeader(value = "X-User-Id", required = false) String userId) {
		authorizationService.requireOwnedAssistant(assistantId, authType, userId);
		toolService.detachTool(assistantId, toolId);
	}
}
