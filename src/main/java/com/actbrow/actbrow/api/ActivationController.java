package com.actbrow.actbrow.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.actbrow.actbrow.api.dto.ActivationStatusResponse;
import com.actbrow.actbrow.service.ActivationStatusService;

@RestController
@RequestMapping("/v1/assistants/{assistantId}/activation")
public class ActivationController {

	private final ActivationStatusService activationStatusService;

	public ActivationController(ActivationStatusService activationStatusService) {
		this.activationStatusService = activationStatusService;
	}

	@GetMapping
	public ActivationStatusResponse status(@PathVariable String assistantId,
		@RequestHeader(value = "X-User-Id", required = false) String userId) {
		return activationStatusService.status(assistantId, userId);
	}
}
