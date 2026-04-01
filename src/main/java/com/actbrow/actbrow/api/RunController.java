package com.actbrow.actbrow.api;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.actbrow.actbrow.agent.ToolExecutionResult;
import com.actbrow.actbrow.api.dto.RunEventResponse;
import com.actbrow.actbrow.api.dto.RunResponse;
import com.actbrow.actbrow.api.dto.ToolResultRequest;
import com.actbrow.actbrow.service.RunEventBroker;
import com.actbrow.actbrow.service.RunService;

import jakarta.validation.Valid;
import reactor.core.publisher.Flux;

@RestController
@Validated
@RequestMapping("/v1/runs")
public class RunController {

	private final RunService runService;
	private final RunEventBroker runEventBroker;

	public RunController(RunService runService, RunEventBroker runEventBroker) {
		this.runService = runService;
		this.runEventBroker = runEventBroker;
	}

	@GetMapping("/{runId}")
	public RunResponse getRun(@PathVariable String runId) {
		return runService.getRun(runId);
	}

	@GetMapping(path = "/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<RunEventResponse>> stream(@PathVariable String runId) {
		runService.requireRun(runId);
		runService.ensureRunStarted(runId);
		return runEventBroker.stream(runId);
	}

	@PostMapping("/{runId}/tool-results")
	public RunResponse submitToolResult(@PathVariable String runId, @Valid @RequestBody ToolResultRequest request) {
		ToolExecutionResult result = new ToolExecutionResult(request.success(), request.structuredOutput(),
			request.textSummary(), request.error());
		runService.submitClientToolResult(runId, request.toolCallId(), result);
		return runService.getRun(runId);
	}
}
