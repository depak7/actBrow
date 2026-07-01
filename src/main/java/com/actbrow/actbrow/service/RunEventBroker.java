package com.actbrow.actbrow.service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;

import com.actbrow.actbrow.api.dto.RunEventResponse;

import jakarta.annotation.PreDestroy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@Component
public class RunEventBroker {

	/** Keep a completed run's replay sink around briefly so late SSE reconnects still get history. */
	private static final long EVICT_AFTER_SECONDS = 120;

	private final Map<String, Sinks.Many<RunEventResponse>> sinks = new ConcurrentHashMap<>();
	private final ScheduledExecutorService evictor = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "run-event-evictor");
		thread.setDaemon(true);
		return thread;
	});

	public void emit(String runId, String eventType, Map<String, Object> payload) {
		RunEventResponse event = new RunEventResponse(runId, eventType, Instant.now(), payload);
		sinks.computeIfAbsent(runId, ignored -> Sinks.many().replay().all()).tryEmitNext(event);
	}

	public void complete(String runId) {
		sinks.computeIfAbsent(runId, ignored -> Sinks.many().replay().all()).tryEmitComplete();
		// Evict after a grace period so the sink (which replays the full event history) does not
		// leak for the process lifetime, while late reconnects within the window still work.
		evictor.schedule(() -> sinks.remove(runId), EVICT_AFTER_SECONDS, TimeUnit.SECONDS);
	}

	/**
	 * Completes any active stream and drops the sink for this run (e.g. conversation deleted).
	 */
	public void dispose(String runId) {
		Sinks.Many<RunEventResponse> sink = sinks.remove(runId);
		if (sink != null) {
			sink.tryEmitComplete();
		}
	}

	@PreDestroy
	void shutdown() {
		evictor.shutdownNow();
	}

	public Flux<ServerSentEvent<RunEventResponse>> stream(String runId) {
		return sinks.computeIfAbsent(runId, ignored -> Sinks.many().replay().all())
			.asFlux()
			.map(event -> ServerSentEvent.<RunEventResponse>builder(event)
				.id(event.createdAt().toString())
				.event(event.eventType())
				.build());
	}
}
