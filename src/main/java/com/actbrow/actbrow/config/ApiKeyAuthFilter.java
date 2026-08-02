package com.actbrow.actbrow.config;

import java.util.Map;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
import com.actbrow.actbrow.service.ApiKeyIdentityResolver;
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

	private final ApiKeyIdentityResolver identityResolver;
	private final ObjectMapper objectMapper;
	private final boolean claudeProxyEnabled;
	private final String claudeProxySecret;

	public ApiKeyAuthFilter(ApiKeyIdentityResolver identityResolver, ObjectMapper objectMapper,
		@Value("${actbrow.claude-proxy.enabled:false}") boolean claudeProxyEnabled,
		@Value("${actbrow.claude-proxy.secret:}") String claudeProxySecret) {
		this.identityResolver = identityResolver;
		this.objectMapper = objectMapper;
		this.claudeProxyEnabled = claudeProxyEnabled;
		this.claudeProxySecret = claudeProxySecret == null ? "" : claudeProxySecret;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		var request = exchange.getRequest();
		String method = request.getMethod().name();
		String path = request.getPath().value();

		if ("OPTIONS".equalsIgnoreCase(method)) {
			return chain.filter(stripIdentityHeaders(exchange));
		}

		if (isPublicRoute(method, path)) {
			log.debug("Allow unauthenticated {} {}", method, path);
			return chain.filter(stripIdentityHeaders(exchange));
		}

		if (segmentsMatch(path, "/claude")) {
			return authenticateClaudeProxy(exchange, chain);
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
				return authenticateWidgetKey(exchange, chain, apiKey, method, path);
			}
			return authenticateAccountKey(exchange, chain, apiKey);
		}
		catch (IllegalArgumentException e) {
			return unauthorized(exchange, e.getMessage());
		}
	}

	private Mono<Void> authenticateClaudeProxy(ServerWebExchange exchange, WebFilterChain chain) {
		if (!claudeProxyEnabled) {
			return unauthorized(exchange, "Claude proxy is disabled");
		}
		String remote = exchange.getRequest().getRemoteAddress() == null ? ""
			: exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
		boolean loopback = "127.0.0.1".equals(remote) || "0:0:0:0:0:0:0:1".equals(remote) || "::1".equals(remote);
		String provided = exchange.getRequest().getHeaders().getFirst("X-Actbrow-Internal-Secret");
		boolean secretOk = !claudeProxySecret.isBlank() && claudeProxySecret.equals(provided);
		if (!loopback && !secretOk) {
			return unauthorized(exchange, "Claude proxy is internal-only");
		}
		return chain.filter(stripIdentityHeaders(exchange));
	}

	private Mono<Void> authenticateSetupKey(ServerWebExchange exchange, WebFilterChain chain, String apiKey,
		String method, String path) {
		if (!isSetupRoute(method, path)) {
			return unauthorized(exchange, "Setup key cannot access this route");
		}
		var identity = identityResolver.resolveSetupKey(apiKey)
			.orElseThrow(() -> new IllegalArgumentException("Invalid setup key"));
		return chain.filter(withAuthHeaders(exchange, "setup", null, identity.assistantId()));
	}

	private Mono<Void> authenticateWidgetKey(ServerWebExchange exchange, WebFilterChain chain, String apiKey,
		String method, String path) {
		if (!isWidgetRoute(method, path)) {
			return unauthorized(exchange, "Widget key cannot access this route");
		}
		var identity = identityResolver.resolveWidgetKey(apiKey)
			.orElseThrow(() -> new IllegalArgumentException("Invalid widget key"));
		if (!originAllowed(exchange, identity.allowedOrigins())) {
			return unauthorized(exchange, "Widget key is not allowed from this origin");
		}
		// Widget auth carries assistant id only — never treat widget as account identity.
		return chain.filter(withAuthHeaders(exchange, "widget", null, identity.assistantId()));
	}

	private Mono<Void> authenticateAccountKey(ServerWebExchange exchange, WebFilterChain chain, String apiKey) {
		var identity = identityResolver.resolveAccountKey(apiKey)
			.orElseThrow(() -> new IllegalArgumentException("Invalid API key"));
		return chain.filter(withAuthHeaders(exchange, "account", identity.userId(), null));
	}

	private ServerWebExchange withAuthHeaders(ServerWebExchange exchange, String authType, String userId,
		String assistantId) {
		return exchange.mutate()
			.request(exchange.getRequest().mutate()
				.headers(h -> {
					h.remove("X-User-Id");
					h.remove("X-Actbrow-Auth-Type");
					h.remove("X-Actbrow-Assistant-Id");
					h.set("X-Actbrow-Auth-Type", authType);
					if (userId != null) {
						h.set("X-User-Id", userId);
					}
					if (assistantId != null) {
						h.set("X-Actbrow-Assistant-Id", assistantId);
					}
				})
				.build())
			.build();
	}

	private ServerWebExchange stripIdentityHeaders(ServerWebExchange exchange) {
		return exchange.mutate()
			.request(exchange.getRequest().mutate()
				.headers(h -> {
					h.remove("X-User-Id");
					h.remove("X-Actbrow-Auth-Type");
					h.remove("X-Actbrow-Assistant-Id");
				})
				.build())
			.build();
	}

	private boolean isPublicRoute(String method, String path) {
		if ("GET".equalsIgnoreCase(method) && "/health".equals(path)) {
			return true;
		}
		if (segmentsMatch(path, "/v1/waitlist")) {
			return true;
		}
		if ("/actbrow-sdk.js".equals(path) || "/actbrow-widget.js".equals(path)) {
			return true;
		}
		if ("GET".equalsIgnoreCase(method) && "/v1/widget/config".equals(path)) {
			return true;
		}
		// Only Google sign-in is public under /auth. /auth/me requires an account key.
		return "POST".equalsIgnoreCase(method) && "/auth/google".equals(path);
	}

	private static boolean isSetupRoute(String method, String path) {
		return "PUT".equalsIgnoreCase(method) && path.matches("/v1/assistants/[^/]+/sync");
	}

	/**
	 * A widget key is public — it ships in the page source of every site that embeds the widget — so
	 * the origin it is used from is the only thing that ties it to a customer. The CORS filter is
	 * deliberately permissive because embedding domains are not known centrally; this is where the
	 * boundary is actually enforced.
	 *
	 * <p>An empty allow-list means unrestricted, so assistants that predate this check and any the
	 * operator has not locked down keep working. A request with no Origin header (server-to-server,
	 * curl) is also allowed through: browsers always send one, and the header is what this defends.
	 */
	private static boolean originAllowed(ServerWebExchange exchange, List<String> allowedOrigins) {
		if (allowedOrigins == null || allowedOrigins.isEmpty()) {
			return true;
		}
		String origin = exchange.getRequest().getHeaders().getOrigin();
		if (origin == null || origin.isBlank()) {
			return true;
		}
		String normalized = normalizeOrigin(origin);
		return allowedOrigins.stream()
			.filter(allowed -> allowed != null && !allowed.isBlank())
			.anyMatch(allowed -> "*".equals(allowed.trim())
				|| normalizeOrigin(allowed).equalsIgnoreCase(normalized));
	}

	/** Trailing slashes and case differ between how operators type an origin and what browsers send. */
	private static String normalizeOrigin(String origin) {
		String trimmed = origin.trim();
		while (trimmed.endsWith("/")) {
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		}
		return trimmed.toLowerCase(java.util.Locale.ROOT);
	}

	private static boolean isWidgetRoute(String method, String path) {
		// The widget reads its own theme at boot so branding changes take effect without the customer
		// re-pasting the embed snippet. GET only: the theme is public branding, but a public key must
		// never be able to rewrite it.
		if ("GET".equalsIgnoreCase(method) && path.matches("/v1/assistants/[^/]+/widget-theme")) {
			return true;
		}
		// The widget reads its own theme at boot so branding changes take effect without the customer
		// re-pasting the embed snippet. Read-only, and the theme is public branding by definition.
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
