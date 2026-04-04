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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.actbrow.actbrow.api.dto.AttachToolRequest;
import com.actbrow.actbrow.api.dto.CreateAssistantToolRequest;
import com.actbrow.actbrow.api.dto.ToolRequest;
import com.actbrow.actbrow.api.dto.ToolResponse;
import com.actbrow.actbrow.service.AssistantService;
import com.actbrow.actbrow.service.ToolService;

import jakarta.validation.Valid;

@RestController
@Validated
@RequestMapping("/v1")
public class ToolController {

	private final ToolService toolService;
	private final AssistantService assistantService;

	public ToolController(ToolService toolService, AssistantService assistantService) {
		this.toolService = toolService;
		this.assistantService = assistantService;
	}

	@PostMapping("/tools")
	@ResponseStatus(HttpStatus.CREATED)
	public ToolResponse create(@Valid @RequestBody ToolRequest request) {
		return toolService.create(request);
	}

	@PostMapping("/tools/attach")
	@ResponseStatus(HttpStatus.CREATED)
	public ToolResponse createAndAttach(@Valid @RequestBody CreateAssistantToolRequest request) {
		assistantService.requireEntity(request.assistantId());
		return toolService.createAndAttach(request.assistantId(), request.toToolRequest());
	}

	@PutMapping("/tools/{toolId}")
	public ToolResponse update(@PathVariable String toolId, @Valid @RequestBody ToolRequest request) {
		return toolService.update(toolId, request);
	}

	@DeleteMapping("/tools/{toolId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable String toolId) {
		toolService.delete(toolId);
	}

	@GetMapping("/tools")
	public List<ToolResponse> list() {
		return toolService.list();
	}

	@GetMapping("/assistants/{assistantId}/tools")
	public List<ToolResponse> listAssistantTools(@PathVariable String assistantId) {
		assistantService.requireEntity(assistantId);
		return toolService.listAssistantTools(assistantId);
	}

	@PostMapping("/assistants/{assistantId}/tools")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void attachTool(@PathVariable String assistantId, @Valid @RequestBody AttachToolRequest request) {
		assistantService.requireEntity(assistantId);
		toolService.attachTool(assistantId, request.toolId());
	}

	@DeleteMapping("/assistants/{assistantId}/tools/{toolId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void detachTool(@PathVariable String assistantId, @PathVariable String toolId) {
		assistantService.requireEntity(assistantId);
		toolService.detachTool(assistantId, toolId);
	}
}
