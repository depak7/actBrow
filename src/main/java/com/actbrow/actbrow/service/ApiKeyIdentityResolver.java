package com.actbrow.actbrow.service;

import java.util.List;
import java.util.Optional;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import com.actbrow.actbrow.config.CacheConfig;
import com.actbrow.actbrow.repository.AssistantRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.actbrow.actbrow.repository.UserRepository;

/**
 * Resolves an API key to the identity behind it, with a short-lived cache.
 *
 * <p>The auth filter runs on every request, so this lookup was the most frequent query in the system
 * — a run that uses client tools pays it again on every SSE reconnect and every tool-result POST,
 * none of which show up in a per-turn query count.
 *
 * <p><b>Misses are deliberately not cached</b> ({@code unless} on each method). Caching "unknown
 * key" would mean a freshly created assistant's widget key returns 401 until the entry expires,
 * which looks exactly like a broken install during onboarding. The cost is that an invalid key still
 * reaches the database each time; that is the right trade for a key-shaped input.
 *
 * <p><b>Revocation lag.</b> Because entries are keyed by the secret itself, a rotated or revoked key
 * keeps working until its entry expires — {@code actbrow.cache.api-key-ttl-seconds}, 30s by default.
 * {@link #evictAll()} exists so a rotation path can make that immediate; call it whenever keys are
 * regenerated or an owner is deleted.
 */
@Service
public class ApiKeyIdentityResolver {

	/**
	 * @param authType   "setup", "widget" or "account" — matches the values the filter forwards
	 * @param userId     set for account keys only; never populated for widget keys
	 * @param assistantId set for setup and widget keys
	 */
	/**
	 * @param allowedOrigins origins the assistant permits its widget key to be used from. Empty means
	 *                       unrestricted — assistants created before origin enforcement, and those an
	 *                       operator has not locked down, must keep working.
	 */
	public record Identity(String authType, String userId, String assistantId, List<String> allowedOrigins) {
	}

	private final AssistantRepository assistantRepository;
	private final UserRepository userRepository;
	private final ObjectMapper objectMapper;

	public ApiKeyIdentityResolver(AssistantRepository assistantRepository, UserRepository userRepository,
		ObjectMapper objectMapper) {
		this.assistantRepository = assistantRepository;
		this.userRepository = userRepository;
		this.objectMapper = objectMapper;
	}

	@Cacheable(cacheNames = CacheConfig.API_KEY_IDENTITY, key = "'setup:' + #apiKey",
		unless = "#result == null")
	public Optional<Identity> resolveSetupKey(String apiKey) {
		return assistantRepository.findBySetupKey(apiKey)
			.map(assistant -> new Identity("setup", null, assistant.getId(), List.of()));
	}

	@Cacheable(cacheNames = CacheConfig.API_KEY_IDENTITY, key = "'widget:' + #apiKey",
		unless = "#result == null")
	public Optional<Identity> resolveWidgetKey(String apiKey) {
		// Widget auth carries assistant id only — never treat widget as account identity.
		return assistantRepository.findByWidgetKey(apiKey)
			.map(assistant -> new Identity("widget", null, assistant.getId(),
				parseOrigins(assistant.getAllowedOriginsJson())));
	}

	@Cacheable(cacheNames = CacheConfig.API_KEY_IDENTITY, key = "'account:' + #apiKey",
		unless = "#result == null")
	public Optional<Identity> resolveAccountKey(String apiKey) {
		return userRepository.findByApiKey(apiKey)
			.map(user -> new Identity("account", user.getId(), null, List.of()));
	}

	/** Tolerant parse: malformed stored JSON must not lock a customer out of their own widget. */
	private List<String> parseOrigins(String json) {
		if (json == null || json.isBlank()) {
			return List.of();
		}
		try {
			return objectMapper.readValue(json, new TypeReference<List<String>>() {
			});
		}
		catch (Exception exception) {
			return List.of();
		}
	}

	/** Drops every cached identity. Use after key rotation so revocation takes effect immediately. */
	@CacheEvict(cacheNames = CacheConfig.API_KEY_IDENTITY, allEntries = true)
	public void evictAll() {
		// Annotation-driven; nothing to do in the body.
	}
}
