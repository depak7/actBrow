package com.actbrow.actbrow.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "actbrow.openrouter")
public record OpenRouterProperties(
	String apiKey,
	String baseUrl,
	String defaultModel,
	Duration requestTimeout,
	String httpReferer,
	String appTitle,
	Reasoning reasoning) {

	/** Mirrors OpenRouter's top-level {@code "reasoning": { "enabled": true }} on chat completions. */
	public record Reasoning(Boolean enabled) {
	}
}
