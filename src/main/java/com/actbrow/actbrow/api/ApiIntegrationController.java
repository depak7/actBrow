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

import com.actbrow.actbrow.api.dto.ApiIntegrationResponse;
import com.actbrow.actbrow.api.dto.ImportApiSpecRequest;
import com.actbrow.actbrow.api.dto.ImportApiSpecResponse;
import com.actbrow.actbrow.service.AssistantService;
import com.actbrow.actbrow.service.OpenApiImportService;

import jakarta.validation.Valid;

@RestController
@Validated
@RequestMapping("/v1")
public class ApiIntegrationController {

	private final OpenApiImportService openApiImportService;
	private final AssistantService assistantService;

	public ApiIntegrationController(OpenApiImportService openApiImportService, AssistantService assistantService) {
		this.openApiImportService = openApiImportService;
		this.assistantService = assistantService;
	}

	@PostMapping("/assistants/{assistantId}/api-integrations/import")
	@ResponseStatus(HttpStatus.CREATED)
	public ImportApiSpecResponse importSpec(@PathVariable String assistantId,
		@Valid @RequestBody ImportApiSpecRequest request) {
		assistantService.requireEntity(assistantId);
		return openApiImportService.importSpec(assistantId, request);
	}

	@GetMapping("/assistants/{assistantId}/api-integrations")
	public List<ApiIntegrationResponse> list(@PathVariable String assistantId) {
		assistantService.requireEntity(assistantId);
		return openApiImportService.listIntegrations(assistantId);
	}

	@DeleteMapping("/assistants/{assistantId}/api-integrations/{integrationId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable String assistantId, @PathVariable String integrationId) {
		assistantService.requireEntity(assistantId);
		openApiImportService.deleteIntegration(assistantId, integrationId);
	}
}
