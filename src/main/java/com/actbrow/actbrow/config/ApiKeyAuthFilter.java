package com.actbrow.actbrow.config;

import java.util.Map;
import java.util.Set;

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

import com.actbrow.actbrow.model.AssistantDefinitionEntity;
import com.actbrow.actbrow.repository.AssistantRepository;
import com.actbrow.actbrow.repository.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiKeyAuthFilter implements WebFilter {

	private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

	private static final Set<String> WIDGET_PREFIXES = Set.of(
		"/v1/conversations",
		"/v1/runs");

	private final UserRepository userRepository;
	private final AssistantRepository assistantRepository;
	private final ObjectMapper objectMapper;

	public ApiKeyAuthFilter(UserRepository userRepository, AssistantRepository assistantRepository,
		ObjectMapper objectMapper) {
		this.userRepository = userRepository;
		this.assistantRepository = assistantRepository;
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
			if (apiKey.startsWith("sk_")) {
				return authenticateSetupKey(exchange, chain, apiKey, method, path);
			}
			if (apiKey.startsWith("wk_")) {
				return authenticateWidgetKey(exchange, chain, apiKey, path);
			}
			return authenticateAccountKey(exchange, chain, apiKey);
		}
		catch (IllegalArgumentException e) {
			return unauthorized(exchange, e.getMessage());
		}
	}

	private Mono<Void> authenticateSetupKey(ServerWebExchange exchange, WebFilterChain chain, String apiKey,
		String method, String path) {
		if (!isSetupRoute(method, path)) {
			return unauthorized(exchange, "Setup key cannot access this route");
		}
		AssistantDefinitionEntity assistant = assistantRepository.findBySetupKey(apiKey)
			.orElseThrow(() -> new IllegalArgumentException("Invalid setup key"));
		ServerWebExchange authenticatedExchange = exchange.mutate()
			.request(exchange.getRequest().mutate()
				.headers(h -> {
					h.remove("X-User-Id");
					h.remove("X-Actbrow-Auth-Type");
					h.remove("X-Actbrow-Assistant-Id");
					h.set("X-Actbrow-Auth-Type", "setup");
					h.set("X-Actbrow-Assistant-Id", assistant.getId());
				})
				.build())
			.build();
		return chain.filter(authenticatedExchange);
	}

	private Mono<Void> authenticateWidgetKey(ServerWebExchange exchange, WebFilterChain chain, String apiKey,
		String path) {
		if (!isWidgetRoute(path)) {
			return unauthorized(exchange, "Widget key cannot access this route");
		}
		AssistantDefinitionEntity assistant = assistantRepository.findByWidgetKey(apiKey)
			.orElseThrow(() -> new IllegalArgumentException("Invalid widget key"));
		ServerWebExchange authenticatedExchange = exchange.mutate()
			.request(exchange.getRequest().mutate()
				.headers(h -> {
					h.remove("X-User-Id");
					h.remove("X-Actbrow-Auth-Type");
					h.remove("X-Actbrow-Assistant-Id");
					h.set("X-Actbrow-Auth-Type", "widget");
					h.set("X-Actbrow-Assistant-Id", assistant.getId());
					h.set("X-User-Id", assistant.getUserId());
				})
				.build())
			.build();
		return chain.filter(authenticatedExchange);
	}

	private Mono<Void> authenticateAccountKey(ServerWebExchange exchange, WebFilterChain chain, String apiKey) {
		var user = userRepository.findByApiKey(apiKey)
			.orElseThrow(() -> new IllegalArgumentException("Invalid API key"));
		ServerWebExchange authenticatedExchange = exchange.mutate()
			.request(exchange.getRequest().mutate()
				.headers(h -> {
					h.remove("X-User-Id");
					h.remove("X-Actbrow-Auth-Type");
					h.remove("X-Actbrow-Assistant-Id");
					h.set("X-Actbrow-Auth-Type", "account");
					h.set("X-User-Id", user.getId());
				})
				.build())
			.build();
		return chain.filter(authenticatedExchange);
	}

	private boolean isPublicRoute(String method, String path) {
		if ("GET".equalsIgnoreCase(method) && "/health".equals(path)) {
			return true;
		}
		if (segmentsMatch(path, "/v1/waitlist")) {
			return true;
		}
		if (segmentsMatch(path, "/v1/assistants")) {
			if (path.endsWith("/sync") || path.endsWith("/connect") || path.endsWith("/export")) {
				return false;
			}
			return true;
		}
		if ("/actbrow-sdk.js".equals(path) || "/actbrow-widget.js".equals(path)) {
			return true;
		}
		if ("GET".equalsIgnoreCase(method) && "/v1/widget/config".equals(path)) {
			return true;
		}
		return segmentsMatch(path, "/auth");
	}

	private static boolean isSetupRoute(String method, String path) {
		return "PUT".equalsIgnoreCase(method) && path.matches("/v1/assistants/[^/]+/sync");
	}

	private static boolean isWidgetRoute(String path) {
		return WIDGET_PREFIXES.stream().anyMatch(prefix -> segmentsMatch(path, prefix));
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
