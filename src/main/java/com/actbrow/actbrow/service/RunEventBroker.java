package com.actbrow.actbrow.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;

import com.actbrow.actbrow.api.dto.RunEventResponse;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@Component
public class RunEventBroker {

	private final Map<String, Sinks.Many<RunEventResponse>> sinks = new ConcurrentHashMap<>();
	private final Map<String, List<RunEventResponse>> history = new ConcurrentHashMap<>();

	public void emit(String runId, String eventType, Map<String, Object> payload) {
		RunEventResponse event = new RunEventResponse(runId, eventType, Instant.now(), payload);
		history.computeIfAbsent(runId, ignored -> new ArrayList<>()).add(event);
		sinks.computeIfAbsent(runId, ignored -> Sinks.many().replay().all()).tryEmitNext(event);
	}

	public void complete(String runId) {
		sinks.computeIfAbsent(runId, ignored -> Sinks.many().replay().all()).tryEmitComplete();
	}

	/**
	 * Completes any active stream and drops replay history for this run (e.g. conversation deleted).
	 */
	public void dispose(String runId) {
		Sinks.Many<RunEventResponse> sink = sinks.remove(runId);
		if (sink != null) {
			sink.tryEmitComplete();
		}
		history.remove(runId);
	}

	public Flux<ServerSentEvent<RunEventResponse>> stream(String runId) {
		return sinks.computeIfAbsent(runId, ignored -> {
			Sinks.Many<RunEventResponse> sink = Sinks.many().replay().all();
			history.getOrDefault(runId, List.of()).forEach(sink::tryEmitNext);
			return sink;
		}).asFlux().map(event -> ServerSentEvent.<RunEventResponse>builder(event)
			.id(event.createdAt().toString())
			.event(event.eventType())
			.build());
	}
}
