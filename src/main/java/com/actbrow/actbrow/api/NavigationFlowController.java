package com.actbrow.actbrow.api;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.actbrow.actbrow.api.dto.NavigationFlowRequest;
import com.actbrow.actbrow.api.dto.NavigationFlowResponse;
import com.actbrow.actbrow.model.AssistantDefinitionEntity;
import com.actbrow.actbrow.service.NavigationFlowService;
import com.actbrow.actbrow.service.ResourceAuthorizationService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/v1/assistants/{assistantId}/flows")
public class NavigationFlowController {

	private final NavigationFlowService navigationFlowService;
	private final ResourceAuthorizationService authorizationService;

	public NavigationFlowController(NavigationFlowService navigationFlowService,
		ResourceAuthorizationService authorizationService) {
		this.navigationFlowService = navigationFlowService;
		this.authorizationService = authorizationService;
	}

	@GetMapping
	public List<NavigationFlowResponse> listFlows(@PathVariable String assistantId,
		@RequestHeader(value = "X-Actbrow-Auth-Type", required = false) String authType,
		@RequestHeader(value = "X-User-Id", required = false) String userId) {
		authorizationService.requireOwnedAssistant(assistantId, authType, userId);
		return navigationFlowService.listByAssistant(assistantId);
	}

	@GetMapping("/enabled")
	public List<NavigationFlowResponse> listEnabledFlows(@PathVariable String assistantId,
		@RequestHeader(value = "X-Actbrow-Auth-Type", required = false) String authType,
		@RequestHeader(value = "X-User-Id", required = false) String userId) {
		authorizationService.requireOwnedAssistant(assistantId, authType, userId);
		return navigationFlowService.listEnabledFlows(assistantId);
	}

	@PostMapping
	public ResponseEntity<NavigationFlowResponse> createFlow(@PathVariable String assistantId,
		@Valid @RequestBody NavigationFlowRequest request,
		@RequestHeader(value = "X-Actbrow-Auth-Type", required = false) String authType,
		@RequestHeader(value = "X-User-Id", required = false) String userId) {
		AssistantDefinitionEntity assistant = authorizationService.requireOwnedAssistant(assistantId, authType, userId);
		NavigationFlowResponse response = navigationFlowService.create(assistantId, request, assistant);
		return ResponseEntity.ok(response);
	}

	@PutMapping("/{flowId}")
	public ResponseEntity<NavigationFlowResponse> updateFlow(@PathVariable String assistantId,
		@PathVariable String flowId, @Valid @RequestBody NavigationFlowRequest request,
		@RequestHeader(value = "X-Actbrow-Auth-Type", required = false) String authType,
		@RequestHeader(value = "X-User-Id", required = false) String userId) {
		AssistantDefinitionEntity assistant = authorizationService.requireOwnedAssistant(assistantId, authType, userId);
		NavigationFlowResponse response = navigationFlowService.update(assistantId, flowId, request, assistant);
		return ResponseEntity.ok(response);
	}

	@DeleteMapping("/{flowId}")
	public ResponseEntity<Void> deleteFlow(@PathVariable String assistantId, @PathVariable String flowId,
		@RequestHeader(value = "X-Actbrow-Auth-Type", required = false) String authType,
		@RequestHeader(value = "X-User-Id", required = false) String userId) {
		authorizationService.requireOwnedAssistant(assistantId, authType, userId);
		navigationFlowService.delete(assistantId, flowId);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/{flowId}/execute")
	public ResponseEntity<Map<String, Object>> executeFlow(@PathVariable String assistantId,
		@PathVariable String flowId,
		@RequestHeader(value = "X-Actbrow-Auth-Type", required = false) String authType,
		@RequestHeader(value = "X-User-Id", required = false) String userId) {
		authorizationService.requireOwnedAssistant(assistantId, authType, userId);
		navigationFlowService.findById(assistantId, flowId);
		return ResponseEntity.ok(Map.of(
			"flowId", flowId,
			"status", "queued",
			"message", "Flow execution will be handled by the assistant"));
	}
}
