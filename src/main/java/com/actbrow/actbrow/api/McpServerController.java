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

import com.actbrow.actbrow.api.dto.McpServerRequest;
import com.actbrow.actbrow.api.dto.McpServerResponse;
import com.actbrow.actbrow.api.dto.McpSyncResponse;
import com.actbrow.actbrow.service.AssistantService;
import com.actbrow.actbrow.service.McpServerService;

import jakarta.validation.Valid;

@RestController
@Validated
@RequestMapping("/v1/assistants/{assistantId}/mcp-servers")
public class McpServerController {

	private final McpServerService mcpServerService;
	private final AssistantService assistantService;

	public McpServerController(McpServerService mcpServerService, AssistantService assistantService) {
		this.mcpServerService = mcpServerService;
		this.assistantService = assistantService;
	}

	@GetMapping
	public List<McpServerResponse> list(@PathVariable String assistantId,
		@RequestHeader(value = "X-User-Id", required = false) String userId) {
		assistantService.requireOwnedEntity(assistantId, userId);
		return mcpServerService.list(assistantId);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public McpServerResponse create(@PathVariable String assistantId,
		@RequestHeader(value = "X-User-Id", required = false) String userId,
		@Valid @RequestBody McpServerRequest request) {
		assistantService.requireOwnedEntity(assistantId, userId);
		return mcpServerService.create(assistantId, request);
	}

	@PutMapping("/{serverId}")
	public McpServerResponse update(@PathVariable String assistantId, @PathVariable String serverId,
		@RequestHeader(value = "X-User-Id", required = false) String userId,
		@Valid @RequestBody McpServerRequest request) {
		assistantService.requireOwnedEntity(assistantId, userId);
		return mcpServerService.update(assistantId, serverId, request);
	}

	@PostMapping("/{serverId}/sync")
	public McpSyncResponse sync(@PathVariable String assistantId, @PathVariable String serverId,
		@RequestHeader(value = "X-User-Id", required = false) String userId) {
		assistantService.requireOwnedEntity(assistantId, userId);
		return mcpServerService.syncTools(assistantId, serverId);
	}

	@DeleteMapping("/{serverId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable String assistantId, @PathVariable String serverId,
		@RequestHeader(value = "X-User-Id", required = false) String userId) {
		assistantService.requireOwnedEntity(assistantId, userId);
		mcpServerService.delete(assistantId, serverId);
	}
}
