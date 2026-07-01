package com.actbrow.actbrow.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import com.actbrow.actbrow.api.dto.AssistantResponse;
import com.actbrow.actbrow.api.dto.CreateAssistantRequest;
import com.actbrow.actbrow.service.AssistantService;

import jakarta.validation.Valid;

@RestController
@Validated
@RequestMapping("/v1/assistants")
public class AssistantController {

	private final AssistantService assistantService;

	public AssistantController(AssistantService assistantService) {
		this.assistantService = assistantService;
	}

	@RequestMapping(method = {RequestMethod.POST, RequestMethod.PUT})
	public AssistantResponse create(@Valid @RequestBody CreateAssistantRequest request) {
		return assistantService.createOrUpdate(request);
	}

	@PutMapping("/{id}")
	public AssistantResponse update(@PathVariable String id, @Valid @RequestBody CreateAssistantRequest request) {
		return assistantService.update(id, request);
	}

	@GetMapping
	public List<AssistantResponse> list(@RequestParam(required = false) String userId) {
		if (userId != null && !userId.isBlank()) {
			return assistantService.listByUser(userId);
		}
		return assistantService.list();
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable String id) {
		assistantService.delete(id);
	}
}
