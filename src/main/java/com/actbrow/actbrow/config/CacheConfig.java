package com.actbrow.actbrow.config;

import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * In-process caching. Exactly one cache is registered, and that is deliberate.
 *
 * <p><b>Only API-key identity is cached.</b> The auth filter resolves a key on every single request —
 * including every SSE reconnect and every client tool-result POST — so this is by far the most
 * frequent query in the system. It caches an immutable record, never an entity.
 *
 * <p><b>Never cache JPA entities.</b> The tool catalog and assistant definition were cached here and
 * removed again. Measured benefit was 2 statements out of 31 on a turn dominated by the model call.
 * The cost was worse than small: {@code AssistantService.requireEntity} returns an entity that
 * callers such as {@code AssistantSyncService} mutate in place before saving, so a cache hit hands
 * two threads the same mutable object and publishes uncommitted field writes to concurrent readers.
 * Hibernate normally isolates callers by returning a fresh instance per transaction; a cache defeats
 * that. If entity caching is ever revisited, cache an immutable projection, not the entity.
 *
 * <p><b>What must never be cached.</b> The {@code runs} row backs the cancellation and terminal
 * compare-and-set in {@code RunRepository.finishIfActive}; caching it would silently break
 * "cancellation is final", the invariant {@code RunRepositoryCasTests} exists to protect. Run memory
 * and conversation messages are written on every step, so a cached copy would make the model plan
 * against a stale transcript. Circuit-breaker and feature-flag state are already in-memory and
 * intentionally volatile.
 *
 * <p><b>Single instance today.</b> Caffeine is per process. Because this goes through Spring's cache
 * abstraction rather than hand-rolled maps, moving to a shared cache later is a dependency plus
 * {@code spring.cache.type=redis}, with no service code changes.
 */
@Configuration
@EnableCaching
public class CacheConfig {

	/**
	 * Resolved identity for an API key. Highest-traffic cache: the auth filter runs on every request,
	 * including every SSE reconnect and client tool-result POST. Kept on the shortest TTL because a
	 * stale entry means a revoked key keeps working until it expires.
	 */
	public static final String API_KEY_IDENTITY = "apiKeyIdentity";

	@Bean
	CaffeineCacheManager cacheManager(
		@Value("${actbrow.cache.api-key-ttl-seconds:30}") long apiKeyTtlSeconds,
		@Value("${actbrow.cache.max-entries:2000}") long maxEntries) {

		// The empty name list plus dynamic=false is the guardrail: a @Cacheable naming anything other
		// than the caches registered below fails fast instead of silently getting an unbounded cache
		// created on demand. That is how "we cached the run row by accident" happens.
		CaffeineCacheManager manager = new CaffeineCacheManager();
		manager.setCacheNames(List.of());

		manager.registerCustomCache(API_KEY_IDENTITY, Caffeine.newBuilder()
			.expireAfterWrite(Duration.ofSeconds(apiKeyTtlSeconds))
			.maximumSize(maxEntries)
			.build());

		return manager;
	}
}
