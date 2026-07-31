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
import com.actbrow.actbrow.service.WidgetThemeService;

@RestController
@RequestMapping("/v1/assistants/{assistantId}/widget-theme")
public class WidgetThemeController {

	private final WidgetThemeService widgetThemeService;

	public WidgetThemeController(WidgetThemeService widgetThemeService) {
		this.widgetThemeService = widgetThemeService;
	}

	@GetMapping
	public WidgetThemeResponse get(@PathVariable String assistantId,
		@RequestHeader(value = "X-User-Id", required = false) String userId) {
		return widgetThemeService.get(assistantId, userId);
	}

	@PutMapping
	public WidgetThemeResponse update(@PathVariable String assistantId,
		@RequestHeader(value = "X-User-Id", required = false) String userId,
		@RequestBody WidgetThemeRequest request) {
		return widgetThemeService.update(assistantId, userId, request == null ? null : request.theme());
	}
}
