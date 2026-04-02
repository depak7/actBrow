package com.actbrow.actbrow.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "actbrow.groq")
public record GroqProperties(
	String apiKey,
	String baseUrl,
	String defaultModel,
	Duration requestTimeout) {
}
