package com.actbrow.actbrow.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.actbrow.actbrow.api.dto.InsightsResponse;
import com.actbrow.actbrow.service.InsightsService;

@RestController
@RequestMapping("/v1/assistants/{assistantId}/insights")
public class InsightsController {

	private final InsightsService insightsService;

	public InsightsController(InsightsService insightsService) {
		this.insightsService = insightsService;
	}

	@GetMapping
	public InsightsResponse insights(@PathVariable String assistantId,
		@RequestHeader(value = "X-User-Id", required = false) String userId) {
		return insightsService.insights(assistantId, userId);
	}
}
