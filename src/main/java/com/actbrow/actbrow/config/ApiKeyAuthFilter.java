package com.actbrow.actbrow.config;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import com.actbrow.actbrow.service.AssistantService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiKeyAuthFilter implements WebFilter {

	private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

	private final AssistantService assistantService;
	private final ObjectMapper objectMapper;

	public ApiKeyAuthFilter(AssistantService assistantService, ObjectMapper objectMapper) {
		this.assistantService = assistantService;
		this.objectMapper = objectMapper;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		var request = exchange.getRequest();
		String method = request.getMethod().name();
		String path = request.getPath().value();

		if ("OPTIONS".equalsIgnoreCase(method)) {
			return chain.filter(exchange);
		}

		if (isPublicRoute(method, path)) {
			log.debug("Allow unauthenticated {} {}", method, path);
			return chain.filter(exchange);
		}

		String apiKey = extractApiKey(exchange);

		if (apiKey == null || apiKey.isBlank()) {
			log.debug("Blocked unauthenticated request: {} {}", method, path);
			return unauthorized(exchange, "Missing API key");
		}

		try {
			var assistant = assistantService.findByApiKey(apiKey);
			// Strip any caller-supplied assistant headers before adding the resolved id
			// to prevent assistant identity spoofing.
			ServerWebExchange authenticatedExchange = exchange.mutate()
				.request(exchange.getRequest().mutate()
					.headers(h -> h.remove("X-Assistant-Id"))
					.header("X-Assistant-Id", assistant.getId())
					.build())
				.build();
			return chain.filter(authenticatedExchange);
		}
		catch (IllegalArgumentException e) {
			return unauthorized(exchange, "Invalid API key");
		}
	}

	/**
	 * Unauthenticated routes. Paths use segment boundaries: {@code /v1/foo} does not match {@code /v1/foobar}.
	 *
	 * Dashboard ops on {@code /v1/assistants} are open at v1 — see the README's "Self-hosted single-developer
	 * mode" note. Wire Google-OAuth-session auth before deploying multi-customer.
	 */
	private boolean isPublicRoute(String method, String path) {
		if ("GET".equalsIgnoreCase(method) && "/health".equals(path)) {
			return true;
		}
		if (segmentsMatch(path, "/v1/waitlist")) {
			return true;
		}
		if (segmentsMatch(path, "/v1/assistants")) {
			return true;
		}
		if ("/actbrow-sdk.js".equals(path) || "/actbrow-widget.js".equals(path)) {
			return true;
		}
		return segmentsMatch(path, "/auth");
	}

	private static boolean segmentsMatch(String path, String prefix) {
		return path.equals(prefix) || path.startsWith(prefix + "/");
	}

	private String extractApiKey(ServerWebExchange exchange) {
		String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");
		if (authHeader != null && authHeader.startsWith("Bearer ")) {
			String raw = authHeader.substring(7).trim();
			return raw.isEmpty() ? null : raw;
		}
		String headerKey = exchange.getRequest().getHeaders().getFirst("X-API-Key");
		if (headerKey != null && !headerKey.isBlank()) {
			return headerKey.trim();
		}
		String queryKey = exchange.getRequest().getQueryParams().getFirst("apiKey");
		if (queryKey == null) {
			return null;
		}
		String trimmed = queryKey.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
		exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
		exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
		try {
			byte[] bytes = objectMapper.writeValueAsBytes(Map.of(
				"error", "Unauthorized",
				"message", message
			));
			return exchange.getResponse()
				.writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
		}
		catch (JsonProcessingException e) {
			log.warn("Failed to serialize unauthorized body", e);
			return exchange.getResponse().setComplete();
		}
	}
}
