package com.actbrow.actbrow.config;

import java.io.IOException;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import com.actbrow.actbrow.service.TenantService;

import reactor.core.publisher.Mono;

@Component
public class ApiKeyAuthFilter implements WebFilter {

	private static final List<String> EXCLUDED_PATHS = List.of(
		"/v1/tenants/validate-key",
		"/v1/tenants",
		"/actbrow-sdk.js",
		"/actbrow-widget.js",
		"/h2-console");

	private final TenantService tenantService;

	public ApiKeyAuthFilter(TenantService tenantService) {
		this.tenantService = tenantService;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		String path = exchange.getRequest().getPath().value();

		if (isExcluded(path)) {
			return chain.filter(exchange);
		}

		String apiKey = extractApiKey(exchange);

		if (apiKey == null || apiKey.isBlank()) {
			return unauthorized(exchange, "Missing API key");
		}

		try {
			var tenant = tenantService.findByApiKey(apiKey);
			if (!tenant.isEnabled()) {
				return unauthorized(exchange, "Tenant is disabled");
			}
			ServerWebExchange authenticatedExchange = exchange.mutate()
				.request(exchange.getRequest().mutate()
					.header("X-Tenant-Id", tenant.getId())
					.header("X-Tenant-Key", tenant.getKey())
					.build())
				.build();
			return chain.filter(authenticatedExchange);
		}
		catch (IllegalArgumentException e) {
			return unauthorized(exchange, "Invalid API key");
		}
	}

	private boolean isExcluded(String path) {
		return EXCLUDED_PATHS.stream().anyMatch(path::startsWith);
	}

	private String extractApiKey(ServerWebExchange exchange) {
		String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");
		if (authHeader != null && authHeader.startsWith("Bearer ")) {
			return authHeader.substring(7);
		}
		return exchange.getRequest().getHeaders().getFirst("X-API-Key");
	}

	private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
		exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
		exchange.getResponse().getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
		String body = "{\"error\": \"Unauthorized\", \"message\": \"" + message + "\"}";
		return exchange.getResponse().writeWith(Mono.just(exchange.getResponse()
			.bufferFactory()
			.wrap(body.getBytes())));
	}
}
