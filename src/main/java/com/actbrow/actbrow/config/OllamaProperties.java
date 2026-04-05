package com.actbrow.actbrow.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "actbrow.ollama")
public record OllamaProperties(
	String baseUrl,
	String defaultModel,
	Duration requestTimeout,
	String apiKey) {
}