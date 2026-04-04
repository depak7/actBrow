package com.actbrow.actbrow.scheduled;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Periodically GETs the configured URL on a virtual thread (e.g. your {@code /health} endpoint).
 */
@Component
@ConditionalOnProperty(name = "actbrow.health.ping-enabled", havingValue = "true")
public class HealthEndpointPingScheduler {

	private static final Logger log = LoggerFactory.getLogger(HealthEndpointPingScheduler.class);

	private final String pingUrl;

	public HealthEndpointPingScheduler(@Value("${actbrow.health.ping-url:}") String pingUrl) {
		this.pingUrl = pingUrl == null ? "" : pingUrl.trim();
	}

	@PostConstruct
	void validate() {
		if (pingUrl.isEmpty()) {
			throw new IllegalStateException(
				"actbrow.health.ping-url must be a full URL when actbrow.health.ping-enabled=true (e.g. http://localhost:8080/health)");
		}
	}

	@Scheduled(fixedRateString = "${actbrow.health.ping-interval-ms:10000}")
	public void pingHealth() {
		Thread.startVirtualThread(this::executePing);
	}

	private void executePing() {
		try {
			HttpClient client = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(2))
				.build();
			HttpRequest request = HttpRequest.newBuilder(URI.create(pingUrl))
				.GET()
				.timeout(Duration.ofSeconds(5))
				.build();
			HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
			if (response.statusCode() >= 400) {
				log.warn("Health ping returned HTTP {}", response.statusCode());
			}
			else {
				log.debug("Health ping OK (HTTP {})", response.statusCode());
			}
		}
		catch (Exception exception) {
			log.warn("Health ping failed: {}", exception.getMessage());
		}
	}
}
