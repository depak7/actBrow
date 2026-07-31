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

import com.actbrow.actbrow.api.dto.ApiIntegrationResponse;
import com.actbrow.actbrow.api.dto.ImportApiSpecRequest;
import com.actbrow.actbrow.api.dto.ImportApiSpecResponse;
import com.actbrow.actbrow.service.OpenApiImportService;
import com.actbrow.actbrow.service.ResourceAuthorizationService;

import jakarta.validation.Valid;

@RestController
@Validated
@RequestMapping("/v1")
public class ApiIntegrationController {

	private final OpenApiImportService openApiImportService;
	private final ResourceAuthorizationService authorizationService;

	public ApiIntegrationController(OpenApiImportService openApiImportService,
		ResourceAuthorizationService authorizationService) {
		this.openApiImportService = openApiImportService;
		this.authorizationService = authorizationService;
	}

	@PostMapping("/assistants/{assistantId}/api-integrations/import")
	@ResponseStatus(HttpStatus.CREATED)
	public ImportApiSpecResponse importSpec(@PathVariable String assistantId,
		@Valid @RequestBody ImportApiSpecRequest request,
		@RequestHeader(value = "X-Actbrow-Auth-Type", required = false) String authType,
		@RequestHeader(value = "X-User-Id", required = false) String userId) {
		authorizationService.requireOwnedAssistant(assistantId, authType, userId);
		return openApiImportService.importSpec(assistantId, request);
	}

	@GetMapping("/assistants/{assistantId}/api-integrations")
	public List<ApiIntegrationResponse> list(@PathVariable String assistantId,
		@RequestHeader(value = "X-Actbrow-Auth-Type", required = false) String authType,
		@RequestHeader(value = "X-User-Id", required = false) String userId) {
		authorizationService.requireOwnedAssistant(assistantId, authType, userId);
		return openApiImportService.listIntegrations(assistantId);
	}

	@DeleteMapping("/assistants/{assistantId}/api-integrations/{integrationId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable String assistantId, @PathVariable String integrationId,
		@RequestHeader(value = "X-Actbrow-Auth-Type", required = false) String authType,
		@RequestHeader(value = "X-User-Id", required = false) String userId) {
		authorizationService.requireOwnedAssistant(assistantId, authType, userId);
		openApiImportService.deleteIntegration(assistantId, integrationId);
	}
}
