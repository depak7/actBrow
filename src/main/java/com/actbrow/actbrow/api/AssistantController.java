package com.actbrow.actbrow.api;

import java.lang.reflect.Method;
import java.util.List;

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

	@GetMapping
	public List<AssistantResponse> list() {
		return assistantService.list();
	}
}
