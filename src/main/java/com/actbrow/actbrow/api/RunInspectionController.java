package com.actbrow.actbrow.api;

import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.actbrow.actbrow.api.dto.RunInspectionResponse;
import com.actbrow.actbrow.api.dto.RunSummaryResponse;
import com.actbrow.actbrow.service.ResourceAuthorizationService;
import com.actbrow.actbrow.service.RunInspectionService;

/**
 * Read-only endpoints backing the dashboard's "why did the agent do that?" view. Kept apart from
 * {@link RunController} because these span two path roots (conversations and runs) and never write.
 */
@RestController
@Validated
@RequestMapping("/v1")
public class RunInspectionController {

	private final RunInspectionService runInspectionService;
	private final ResourceAuthorizationService authorizationService;

	public RunInspectionController(RunInspectionService runInspectionService,
		ResourceAuthorizationService authorizationService) {
		this.runInspectionService = runInspectionService;
		this.authorizationService = authorizationService;
	}

	@GetMapping("/conversations/{conversationId}/runs")
	public List<RunSummaryResponse> listConversationRuns(@PathVariable String conversationId,
		@RequestHeader(value = "X-Actbrow-Auth-Type", required = false) String authType,
		@RequestHeader(value = "X-Actbrow-Assistant-Id", required = false) String authAssistantId,
		@RequestHeader(value = "X-User-Id", required = false) String userId) {
		authorizationService.requireAccessibleConversation(conversationId, authType, userId, authAssistantId);
		return runInspectionService.listConversationRuns(conversationId);
	}

	@GetMapping("/runs/{runId}/steps")
	public RunInspectionResponse inspectRun(@PathVariable String runId,
		@RequestHeader(value = "X-Actbrow-Auth-Type", required = false) String authType,
		@RequestHeader(value = "X-Actbrow-Assistant-Id", required = false) String authAssistantId,
		@RequestHeader(value = "X-User-Id", required = false) String userId) {
		authorizationService.requireAccessibleRun(runId, authType, userId, authAssistantId);
		return runInspectionService.inspect(runId);
	}
}
