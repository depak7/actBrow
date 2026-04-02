package com.actbrow.actbrow.api;

import java.lang.reflect.Method;
import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import com.actbrow.actbrow.api.dto.AssistantResponse;
import com.actbrow.actbrow.api.dto.CreateAssistantRequest;
import com.actbrow.actbrow.model.UserEntity;
import com.actbrow.actbrow.service.AssistantService;
import com.actbrow.actbrow.service.UserService;

import jakarta.validation.Valid;

@RestController
@Validated
@RequestMapping("/v1/assistants")
public class AssistantController {

	private final AssistantService assistantService;
	private final UserService userService;

	public AssistantController(AssistantService assistantService, UserService userService) {
		this.assistantService = assistantService;
		this.userService = userService;
	}

	@RequestMapping(method = {RequestMethod.POST, RequestMethod.PUT})
	public AssistantResponse create(
		@Valid @RequestBody CreateAssistantRequest request,
		@AuthenticationPrincipal OidcUser user
	) {
		UserEntity userEntity = userService.findOrCreateUser(user);
		return assistantService.createOrUpdate(request, userEntity.getId());
	}

	@GetMapping
	public List<AssistantResponse> list(
		@RequestParam(required = false) String tenantId,
		@AuthenticationPrincipal OidcUser user
	) {
		UserEntity userEntity = userService.findOrCreateUser(user);
		// Get user's tenant
		var tenantOpt = userService.findTenantByUserId(userEntity.getId());
		if (tenantOpt.isPresent()) {
			return assistantService.listByTenant(tenantOpt.get().getId());
		}
		return List.of();
	}
}
