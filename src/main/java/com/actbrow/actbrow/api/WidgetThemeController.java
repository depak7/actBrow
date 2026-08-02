package com.actbrow.actbrow.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.actbrow.actbrow.api.dto.WidgetThemeRequest;
import com.actbrow.actbrow.api.dto.WidgetThemeResponse;
import com.actbrow.actbrow.api.NotFoundException;
import com.actbrow.actbrow.service.WidgetThemeService;

@RestController
@RequestMapping("/v1/assistants/{assistantId}/widget-theme")
public class WidgetThemeController {

	private final WidgetThemeService widgetThemeService;

	public WidgetThemeController(WidgetThemeService widgetThemeService) {
		this.widgetThemeService = widgetThemeService;
	}

	/**
	 * Read the theme. The embedded widget calls this at boot with its widget key, so branding changes
	 * apply without the customer re-pasting the embed snippet. A widget key has no owning user, and
	 * the auth filter has already bound it to this assistant, so ownership is only checked for
	 * account callers. Writes still require an account key.
	 */
	@GetMapping
	public WidgetThemeResponse get(@PathVariable String assistantId,
		@RequestHeader(value = "X-Actbrow-Auth-Type", required = false) String authType,
		@RequestHeader(value = "X-Actbrow-Assistant-Id", required = false) String authAssistantId,
		@RequestHeader(value = "X-User-Id", required = false) String userId) {
		if ("widget".equals(authType)) {
			if (!assistantId.equals(authAssistantId)) {
				throw new NotFoundException("Assistant not found");
			}
			return widgetThemeService.getForWidget(assistantId);
		}
		return widgetThemeService.get(assistantId, userId);
	}

	@PutMapping
	public WidgetThemeResponse update(@PathVariable String assistantId,
		@RequestHeader(value = "X-User-Id", required = false) String userId,
		@RequestBody WidgetThemeRequest request) {
		return widgetThemeService.update(assistantId, userId, request == null ? null : request.theme());
	}
}
